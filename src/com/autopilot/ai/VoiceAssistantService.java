// Copyright (C) 2026 DeYouOS
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.autopilot.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.audio.asr.recognition.timestamp.Sentence;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisResult;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.common.ResultCallback;

import ai.z.openapi.ZaiClient;
import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatMessage;
import ai.z.openapi.service.model.ChatMessageRole;
import ai.z.openapi.service.model.Choice;
import ai.z.openapi.service.model.Delta;
import ai.z.openapi.service.model.ModelData;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 车载 AI 语音助手前台服务
 *
 * 实现完整的语音对话闭环：
 * 麦克风录音 → DashScope ASR 实时识别 → 智谱 GLM 流式对话 → DashScope TTS 语音合成 → AudioTrack 播放
 *
 * 状态机流转：IDLE → LISTENING → THINKING → SPEAKING → IDLE
 *
 * 各模块：
 * 1. AudioRecord — 16kHz/16bit/mono 麦克风录制
 * 2. DashScope ASR (paraformer-realtime-v2) — 实时语音转文字
 * 3. 智谱 GLM (glm-4-flash) — 大模型流式对话
 * 4. DashScope TTS v2 (cosyvoice-v2) — 流式语音合成
 * 5. AudioTrack — 22050Hz/16bit/mono PCM 播放
 * 6. 跨进程 Broadcast — 联动 Launcher3 机器人动画
 */
public class VoiceAssistantService extends Service {

    private static final String TAG = "VoiceAssistant";

    // ========== 前台服务通知 ==========

    /** 前台服务通知渠道 ID */
    private static final String CHANNEL_ID = "voice_assistant";

    /** 前台服务通知 ID */
    private static final int NOTIFICATION_ID = 2001;

    // ========== Intent Action 常量 ==========

    /** 开始语音监听（从 IDLE 进入 LISTENING） */
    public static final String ACTION_START_LISTENING =
            "com.autopilot.action.START_LISTENING";

    /** 停止当前流程，回到 IDLE */
    public static final String ACTION_STOP =
            "com.autopilot.action.STOP";

    /** 直接测试 LLM 对话（跳过 ASR，从 Intent extra 获取文本） */
    public static final String ACTION_TEST_LLM =
            "com.autopilot.action.TEST_LLM";

    /** ACTION_TEST_LLM 携带的用户文本 extra key */
    public static final String EXTRA_TEST_TEXT = "test_text";

    // ========== 跨进程 Broadcast 常量（控制 Launcher3 机器人动画） ==========

    /** 发送给 Launcher3 的机器人控制 action */
    private static final String ACTION_ROBOT_COMMAND =
            "com.autopilot.action.ROBOT_COMMAND";

    /** 命令类型 extra key */
    private static final String EXTRA_COMMAND_TYPE = "command_type";

    /** AI 情绪 extra key */
    private static final String EXTRA_AI_EMOTION = "ai_emotion";

    /** TTS 振幅 extra key（用于驱动嘴巴动画） */
    private static final String EXTRA_TTS_AMPLITUDE = "tts_amplitude";

    // ========== API Keys（测试阶段硬编码） ==========

    /** 阿里云 DashScope API Key（ASR + TTS） */
    private static final String DASHSCOPE_API_KEY =
            "sk-sp-b56690407112420aa2488481c99fd9fd";

    /** 智谱 GLM API Key */
    private static final String ZHIPU_API_KEY =
            "231827a25b5b43bbac9885afeec0d651.CLwy5B9npZ16IwRc";

    // ========== 系统提示词 ==========

    /** LLM 系统提示词：定义 AI 助手人设 */
    private static final String SYSTEM_PROMPT =
            "你是车载 AI 助手小 P，运行在 AutoPilot 车载系统中。\n"
            + "回复要求：简洁明了，不超过 100 字，使用友好语气。";

    // ========== 音频参数 ==========

    /** ASR 录音采样率（paraformer-realtime-v2 要求 16kHz） */
    private static final int ASR_SAMPLE_RATE = 16000;

    /** TTS 播放采样率（cosyvoice-v2 PCM 输出 22050Hz） */
    private static final int TTS_SAMPLE_RATE = 22050;

    /** 录音缓冲区读取间隔（每次读取 20ms 的音频数据） */
    private static final int RECORD_BUFFER_DURATION_MS = 20;

    /** 录音每次读取的字节数：16kHz * 16bit * mono * 20ms = 640 bytes */
    private static final int RECORD_READ_SIZE =
            ASR_SAMPLE_RATE * 2 * RECORD_BUFFER_DURATION_MS / 1000;

    // ========== 语音交互状态机 ==========

    /**
     * 语音交互状态枚举
     *
     * 状态流转：IDLE → LISTENING → THINKING → SPEAKING → IDLE
     * 任何状态都可通过 ACTION_STOP 回到 IDLE
     */
    private enum VoiceState {
        /** 空闲：等待唤醒或手动触发 */
        IDLE,
        /** 录音中：麦克风采集 + ASR 实时识别 */
        LISTENING,
        /** 思考中：ASR 识别完成，LLM 正在生成回复 */
        THINKING,
        /** 播放中：TTS 合成 + AudioTrack 播放 */
        SPEAKING
    }

    /** 当前状态（volatile 保证跨线程可见性） */
    private volatile VoiceState mState = VoiceState.IDLE;

    // ========== 线程模型 ==========

    /** 状态机管理线程（所有状态转换在此线程上串行执行） */
    private HandlerThread mStateMachineThread;
    private Handler mStateMachineHandler;

    /** 录音线程（AudioRecord.read 阻塞循环） */
    private HandlerThread mRecordThread;
    private Handler mRecordHandler;

    // ========== 音频组件 ==========

    /** 麦克风录音器（16kHz/16bit/mono） */
    private AudioRecord mAudioRecord;

    /** 音频播放器（22050Hz/16bit/mono，流模式） */
    private AudioTrack mAudioTrack;

    /** 录音循环控制标志 */
    private final AtomicBoolean mIsRecording = new AtomicBoolean(false);

    /** WakeLock 防止语音交互过程中 CPU 休眠 */
    private PowerManager.WakeLock mWakeLock;

    // ========== SDK 客户端 ==========

    /** 智谱 GLM 客户端（线程安全，全局复用） */
    private ZaiClient mZaiClient;

    /** DashScope ASR 实例（每次识别创建新实例） */
    private volatile Recognition mRecognition;

    /** TTS 合成器（每次合成创建新实例） */
    private volatile SpeechSynthesizer mSpeechSynthesizer;

    // ========== Service 生命周期 ==========

    /**
     * 服务创建时初始化所有组件
     * 创建前台通知、线程池、SDK 客户端
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "VoiceAssistantService 创建");

        // 创建通知渠道并启动前台服务
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("AI 语音助手待命中"));

        // 初始化线程
        initThreads();

        // 初始化 WakeLock（防止录音/播放时 CPU 休眠）
        initWakeLock();

        // 初始化智谱 GLM 客户端（全局复用）
        initZaiClient();

        Log.i(TAG, "VoiceAssistantService 初始化完成，等待触发");
    }

    /**
     * 处理外部发来的 Intent 命令
     *
     * 支持三种 action：
     * - ACTION_START_LISTENING: 开始语音监听
     * - ACTION_STOP: 停止当前流程
     * - ACTION_TEST_LLM: 直接测试 LLM（跳过录音和 ASR）
     *
     * @param intent  启动 Intent
     * @param flags   启动标志
     * @param startId 启动 ID
     * @return START_STICKY 系统杀死后自动重启
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.w(TAG, "收到 null Intent，忽略");
            return START_STICKY;
        }

        String action = intent.getAction();
        if (action == null) {
            Log.i(TAG, "VoiceAssistantService 启动（无 action）");
            return START_STICKY;
        }

        Log.i(TAG, "收到命令: " + action + ", 当前状态: " + mState);

        switch (action) {
            case ACTION_START_LISTENING:
                // 在状态机线程上执行，避免主线程阻塞
                mStateMachineHandler.post(this::handleStartListening);
                break;

            case ACTION_STOP:
                mStateMachineHandler.post(this::handleStop);
                break;

            case ACTION_TEST_LLM:
                String testText = intent.getStringExtra(EXTRA_TEST_TEXT);
                if (testText != null && !testText.isEmpty()) {
                    mStateMachineHandler.post(() -> handleTestLlm(testText));
                } else {
                    Log.w(TAG, "ACTION_TEST_LLM 缺少 test_text extra");
                }
                break;

            default:
                Log.w(TAG, "未知 action: " + action);
                break;
        }

        return START_STICKY;
    }

    /**
     * 服务销毁时释放所有资源
     * 停止录音、释放音频设备、关闭 SDK 客户端、退出线程
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "VoiceAssistantService 销毁，释放资源");

        // 停止当前活动
        stopAllActivities();

        // 释放智谱客户端
        if (mZaiClient != null) {
            try {
                mZaiClient.close();
            } catch (Exception e) {
                Log.e(TAG, "关闭智谱客户端失败", e);
            }
            mZaiClient = null;
        }

        // 释放 WakeLock
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }

        // 退出线程
        if (mRecordThread != null) {
            mRecordThread.quitSafely();
        }
        if (mStateMachineThread != null) {
            mStateMachineThread.quitSafely();
        }
    }

    /** 不支持绑定模式 */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ========== 初始化方法 ==========

    /**
     * 初始化工作线程
     * - 状态机线程：管理状态流转和 SDK 调用
     * - 录音线程：AudioRecord.read() 阻塞循环
     */
    private void initThreads() {
        mStateMachineThread = new HandlerThread("VoiceStateMachine");
        mStateMachineThread.start();
        mStateMachineHandler = new Handler(mStateMachineThread.getLooper());

        mRecordThread = new HandlerThread("VoiceRecord",
                android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        mRecordThread.start();
        mRecordHandler = new Handler(mRecordThread.getLooper());
    }

    /**
     * 初始化 WakeLock
     * 使用 PARTIAL_WAKE_LOCK 防止 CPU 在语音交互过程中休眠
     */
    private void initWakeLock() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AutoPilot:VoiceAssistant");
            mWakeLock.setReferenceCounted(false);
        }
    }

    /**
     * 初始化智谱 GLM 客户端
     * 使用 ofZHIPU() 配置智谱 API 端点，全局复用同一实例
     */
    private void initZaiClient() {
        try {
            mZaiClient = new ZaiClient.Builder()
                    .apiKey(ZHIPU_API_KEY)
                    .ofZHIPU()
                    .build();
            Log.i(TAG, "智谱 GLM 客户端初始化成功");
        } catch (Exception e) {
            Log.e(TAG, "智谱 GLM 客户端初始化失败", e);
        }
    }

    // ========== 状态机命令处理 ==========

    /**
     * 处理开始监听命令
     * 只有 IDLE 状态才能进入 LISTENING
     */
    private void handleStartListening() {
        if (mState != VoiceState.IDLE) {
            Log.w(TAG, "当前状态 " + mState + " 不允许开始监听，需先停止");
            return;
        }

        Log.i(TAG, "开始语音监听");
        transitionTo(VoiceState.LISTENING);
        acquireWakeLock();

        // 通知 Launcher3 机器人进入倾听状态
        sendRobotEmotion("LISTENING");

        // 启动 ASR + 录音
        startAsrAndRecord();
    }

    /**
     * 处理停止命令
     * 从任意状态回到 IDLE，释放所有正在使用的资源
     */
    private void handleStop() {
        Log.i(TAG, "收到停止命令，当前状态: " + mState);
        stopAllActivities();
        transitionTo(VoiceState.IDLE);
        releaseWakeLock();
        updateNotification("AI 语音助手待命中");

        // 通知 Launcher3 机器人回到空闲
        sendRobotEmotion("IDLE");
    }

    /**
     * 处理 LLM 直接测试命令
     * 跳过 ASR，直接用给定文本调用 LLM
     *
     * @param userText 用户输入的测试文本
     */
    private void handleTestLlm(String userText) {
        if (mState != VoiceState.IDLE) {
            Log.w(TAG, "当前状态 " + mState + " 不允许测试 LLM，需先停止");
            return;
        }

        Log.i(TAG, "直接测试 LLM，用户文本: " + userText);
        transitionTo(VoiceState.THINKING);
        acquireWakeLock();
        updateNotification("AI 正在思考...");

        // 通知 Launcher3 机器人进入思考状态
        sendRobotEmotion("THINKING");

        // 直接调用 LLM
        processLlmAndSpeak(userText);
    }

    // ========== ASR 语音识别 ==========

    /**
     * 启动 ASR 实时识别和麦克风录音
     *
     * 流程：
     * 1. 创建 DashScope Recognition 实例
     * 2. 配置回调：接收中间结果和最终结果
     * 3. 启动 AudioRecord 录音循环
     * 4. 录音数据通过 sendAudioFrame() 发送给 ASR
     * 5. ASR 检测到句尾（isSentenceEnd）时，停止录音并转入 THINKING
     */
    private void startAsrAndRecord() {
        // 用于收集最终识别结果
        AtomicReference<String> finalText = new AtomicReference<>("");

        try {
            // 创建 ASR 实例
            mRecognition = new Recognition();

            // 构建 ASR 参数
            RecognitionParam asrParam = RecognitionParam.builder()
                    .model("paraformer-realtime-v2")
                    .apiKey(DASHSCOPE_API_KEY)
                    .sampleRate(ASR_SAMPLE_RATE)
                    .format("pcm")
                    .build();

            // 启动 ASR（回调模式）
            mRecognition.call(asrParam, new ResultCallback<RecognitionResult>() {
                @Override
                public void onEvent(RecognitionResult result) {
                    Sentence sentence = result.getSentence();
                    if (sentence == null) {
                        return;
                    }

                    String text = sentence.getText();
                    if (text == null || text.isEmpty()) {
                        return;
                    }

                    Log.d(TAG, "ASR 识别中: " + text
                            + " (isSentenceEnd=" + result.isSentenceEnd() + ")");

                    if (result.isSentenceEnd()) {
                        // 一句话识别完成
                        finalText.set(text);
                        Log.i(TAG, "ASR 识别完成: " + text);

                        // 停止录音和 ASR
                        stopRecording();
                        stopAsr();

                        // 切换到 THINKING 状态，调用 LLM
                        mStateMachineHandler.post(() -> {
                            if (mState == VoiceState.LISTENING) {
                                String recognized = finalText.get();
                                if (recognized != null && !recognized.isEmpty()) {
                                    transitionTo(VoiceState.THINKING);
                                    updateNotification("AI 正在思考...");
                                    sendRobotEmotion("THINKING");
                                    processLlmAndSpeak(recognized);
                                } else {
                                    Log.w(TAG, "ASR 识别文本为空，回到 IDLE");
                                    transitionTo(VoiceState.IDLE);
                                    releaseWakeLock();
                                    updateNotification("AI 语音助手待命中");
                                    sendRobotEmotion("IDLE");
                                }
                            }
                        });
                    }
                }

                @Override
                public void onComplete() {
                    Log.i(TAG, "ASR 识别会话结束");
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "ASR 识别出错", e);
                    mStateMachineHandler.post(() -> {
                        if (mState == VoiceState.LISTENING) {
                            transitionTo(VoiceState.IDLE);
                            releaseWakeLock();
                            updateNotification("ASR 识别出错");
                            sendRobotEmotion("ERROR");
                        }
                    });
                }
            });

            Log.i(TAG, "ASR 启动成功，开始录音");
            updateNotification("正在聆听...");

            // 启动麦克风录音
            startRecording();

        } catch (Exception e) {
            Log.e(TAG, "启动 ASR 失败", e);
            transitionTo(VoiceState.IDLE);
            releaseWakeLock();
            updateNotification("ASR 启动失败");
            sendRobotEmotion("ERROR");
        }
    }

    // ========== 麦克风录音 ==========

    /**
     * 启动 AudioRecord 录音循环
     *
     * 在录音线程上运行，每 20ms 读取一次 PCM 数据，
     * 通过 recognition.sendAudioFrame() 实时发送给 ASR 引擎
     */
    private void startRecording() {
        // 计算最小缓冲区大小
        int minBufferSize = AudioRecord.getMinBufferSize(
                ASR_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        // 使用较大缓冲区避免溢出（至少 4 倍最小值）
        int bufferSize = Math.max(minBufferSize * 4, RECORD_READ_SIZE * 8);

        try {
            mAudioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    ASR_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);

            if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败");
                return;
            }

            mAudioRecord.startRecording();
            mIsRecording.set(true);

            Log.i(TAG, "AudioRecord 启动，缓冲区: " + bufferSize + " bytes");

            // 在录音线程上执行读取循环
            mRecordHandler.post(this::recordingLoop);

        } catch (SecurityException e) {
            Log.e(TAG, "缺少 RECORD_AUDIO 权限", e);
        } catch (Exception e) {
            Log.e(TAG, "启动录音失败", e);
        }
    }

    /**
     * 录音循环：持续读取 PCM 数据并发送给 ASR
     * 在录音线程上运行，直到 mIsRecording 被设为 false
     */
    private void recordingLoop() {
        byte[] buffer = new byte[RECORD_READ_SIZE];

        Log.d(TAG, "录音循环开始");

        while (mIsRecording.get()) {
            if (mAudioRecord == null
                    || mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                break;
            }

            int bytesRead = mAudioRecord.read(buffer, 0, RECORD_READ_SIZE);

            if (bytesRead > 0) {
                // 将 PCM 数据发送给 ASR 引擎
                Recognition recognition = mRecognition;
                if (recognition != null && mIsRecording.get()) {
                    try {
                        recognition.sendAudioFrame(
                                ByteBuffer.wrap(buffer, 0, bytesRead));
                    } catch (Exception e) {
                        Log.e(TAG, "发送音频帧给 ASR 失败", e);
                        break;
                    }
                }
            } else if (bytesRead < 0) {
                Log.e(TAG, "AudioRecord.read 返回错误: " + bytesRead);
                break;
            }
        }

        Log.d(TAG, "录音循环结束");
    }

    /**
     * 停止录音
     * 设置标志位让录音循环退出，然后停止并释放 AudioRecord
     */
    private void stopRecording() {
        mIsRecording.set(false);

        if (mAudioRecord != null) {
            try {
                if (mAudioRecord.getRecordingState()
                        == AudioRecord.RECORDSTATE_RECORDING) {
                    mAudioRecord.stop();
                }
                mAudioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "停止 AudioRecord 失败", e);
            }
            mAudioRecord = null;
            Log.d(TAG, "AudioRecord 已释放");
        }
    }

    /**
     * 停止 ASR 识别
     */
    private void stopAsr() {
        Recognition recognition = mRecognition;
        if (recognition != null) {
            try {
                recognition.stop();
            } catch (Exception e) {
                Log.e(TAG, "停止 ASR 失败", e);
            }
            mRecognition = null;
            Log.d(TAG, "ASR 已停止");
        }
    }

    // ========== LLM 大模型对话 ==========

    /**
     * 调用智谱 GLM 进行流式对话，并将回复发送给 TTS 合成
     *
     * 流程：
     * 1. 构建包含系统提示词和用户消息的请求
     * 2. 调用 GLM 流式 API，逐 token 接收回复
     * 3. 将回复文本按句拆分，边收 token 边送 TTS 合成
     * 4. TTS 合成的音频通过 AudioTrack 实时播放
     *
     * @param userText ASR 识别到的用户文本
     */
    private void processLlmAndSpeak(String userText) {
        if (mZaiClient == null) {
            Log.e(TAG, "智谱客户端未初始化");
            transitionTo(VoiceState.IDLE);
            releaseWakeLock();
            return;
        }

        // 构建消息列表
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage(ChatMessageRole.SYSTEM.value(), SYSTEM_PROMPT));
        messages.add(new ChatMessage(ChatMessageRole.USER.value(), userText));

        // 构建请求参数
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model("glm-4-flash")
                .stream(true)
                .messages(messages)
                .build();

        try {
            Log.i(TAG, "调用智谱 GLM，用户输入: " + userText);

            // 调用流式 API
            ChatCompletionResponse response =
                    mZaiClient.chat().createChatCompletion(params);

            if (!response.isSuccess()) {
                Log.e(TAG, "GLM 调用失败: code=" + response.getCode()
                        + ", msg=" + response.getMsg());
                transitionTo(VoiceState.IDLE);
                releaseWakeLock();
                updateNotification("LLM 调用失败");
                sendRobotEmotion("ERROR");
                return;
            }

            // 获取流式 Flowable
            io.reactivex.rxjava3.core.Flowable<ModelData> flowable =
                    response.getFlowable();

            if (flowable == null) {
                // 非流式响应，直接取完整回复
                ModelData data = response.getData();
                if (data != null && data.getChoices() != null
                        && !data.getChoices().isEmpty()) {
                    Choice choice = data.getChoices().get(0);
                    ChatMessage msg = choice.getMessage();
                    if (msg != null && msg.getContent() != null) {
                        String fullReply = msg.getContent().toString();
                        Log.i(TAG, "GLM 非流式回复: " + fullReply);
                        startSpeaking(fullReply);
                        return;
                    }
                }
                Log.w(TAG, "GLM 响应为空");
                transitionTo(VoiceState.IDLE);
                releaseWakeLock();
                return;
            }

            // 流式接收 token，拼接完整回复
            StringBuilder fullReply = new StringBuilder();
            // 用于 TTS 断句的缓冲区
            StringBuilder sentenceBuffer = new StringBuilder();
            // 断句标点符号
            String sentenceBreakers = "。！？；，、.!?;,";

            // 同步阻塞等待流式完成（在状态机线程上执行）
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> errorRef = new AtomicReference<>(null);
            // 标记是否已开始 TTS
            AtomicBoolean ttsStarted = new AtomicBoolean(false);

            // 先准备 TTS（在开始接收 token 之前初始化）
            SpeechSynthesizer synthesizer = createTtsSynthesizer();
            if (synthesizer == null) {
                Log.e(TAG, "创建 TTS 合成器失败");
                transitionTo(VoiceState.IDLE);
                releaseWakeLock();
                return;
            }
            mSpeechSynthesizer = synthesizer;

            // 进入 SPEAKING 状态
            transitionTo(VoiceState.SPEAKING);
            updateNotification("AI 正在回答...");
            sendRobotEmotion("SPEAKING");

            // 初始化 AudioTrack
            initAudioTrack();

            flowable.subscribe(
                    modelData -> {
                        // 处理每个流式 token
                        if (mState != VoiceState.SPEAKING) {
                            return;  // 已被中断
                        }

                        List<Choice> choices = modelData.getChoices();
                        if (choices == null || choices.isEmpty()) {
                            return;
                        }

                        Delta delta = choices.get(0).getDelta();
                        if (delta == null) {
                            return;
                        }

                        String content = delta.getContent();
                        if (content == null || content.isEmpty()) {
                            return;
                        }

                        fullReply.append(content);
                        sentenceBuffer.append(content);

                        // 检查是否有断句标点，有则将该句发送给 TTS
                        String bufStr = sentenceBuffer.toString();
                        int lastBreak = -1;
                        for (int i = bufStr.length() - 1; i >= 0; i--) {
                            if (sentenceBreakers.indexOf(bufStr.charAt(i)) >= 0) {
                                lastBreak = i;
                                break;
                            }
                        }

                        if (lastBreak >= 0) {
                            // 截取到断句标点的部分发送 TTS
                            String sentenceToSpeak =
                                    bufStr.substring(0, lastBreak + 1);
                            sentenceBuffer.delete(0, lastBreak + 1);

                            if (!sentenceToSpeak.isEmpty()) {
                                Log.d(TAG, "TTS 发送句子: " + sentenceToSpeak);
                                ttsStarted.set(true);
                                synthesizer.streamingCall(sentenceToSpeak);
                            }
                        }
                    },
                    error -> {
                        // 流式出错
                        Log.e(TAG, "GLM 流式响应出错", (Throwable) error);
                        errorRef.set(error.toString());
                        latch.countDown();
                    },
                    () -> {
                        // 流式完成
                        Log.i(TAG, "GLM 流式回复完成: " + fullReply.toString());

                        // 将剩余缓冲区的文本发送给 TTS
                        String remaining = sentenceBuffer.toString().trim();
                        if (!remaining.isEmpty()) {
                            Log.d(TAG, "TTS 发送剩余文本: " + remaining);
                            ttsStarted.set(true);
                            synthesizer.streamingCall(remaining);
                        }

                        // 如果有内容发送给 TTS，通知 TTS 流式输入完毕
                        if (ttsStarted.get()) {
                            synthesizer.streamingComplete();
                        }

                        latch.countDown();
                    }
            );

            // 等待流式完成（最多 60 秒超时）
            boolean completed = latch.await(60, TimeUnit.SECONDS);

            if (!completed) {
                Log.w(TAG, "GLM 流式响应超时（60秒）");
            }

            String error = errorRef.get();
            if (error != null) {
                Log.e(TAG, "GLM 流式出错: " + error);
                stopSpeaking();
                transitionTo(VoiceState.IDLE);
                releaseWakeLock();
                updateNotification("LLM 出错");
                sendRobotEmotion("ERROR");
                return;
            }

            // 如果没有任何内容发送给 TTS（极端情况）
            if (!ttsStarted.get()) {
                Log.w(TAG, "GLM 没有返回任何内容");
                stopSpeaking();
                transitionTo(VoiceState.IDLE);
                releaseWakeLock();
                updateNotification("AI 语音助手待命中");
                sendRobotEmotion("IDLE");
            }
            // 否则等 TTS 的 onComplete 回调来结束 SPEAKING 状态

        } catch (Exception e) {
            Log.e(TAG, "LLM 对话流程异常", e);
            stopSpeaking();
            transitionTo(VoiceState.IDLE);
            releaseWakeLock();
            updateNotification("AI 对话出错");
            sendRobotEmotion("ERROR");
        }
    }

    /**
     * 非流式模式启动 TTS（用于非流式 LLM 响应或测试）
     * 将完整文本一次性发送给 TTS 合成
     *
     * @param text 要合成播放的文本
     */
    private void startSpeaking(String text) {
        if (text == null || text.isEmpty()) {
            Log.w(TAG, "TTS 文本为空，跳过");
            transitionTo(VoiceState.IDLE);
            releaseWakeLock();
            return;
        }

        transitionTo(VoiceState.SPEAKING);
        updateNotification("AI 正在回答...");
        sendRobotEmotion("SPEAKING");

        // 初始化 AudioTrack
        initAudioTrack();

        // 创建 TTS 合成器
        SpeechSynthesizer synthesizer = createTtsSynthesizer();
        if (synthesizer == null) {
            Log.e(TAG, "创建 TTS 合成器失败");
            transitionTo(VoiceState.IDLE);
            releaseWakeLock();
            return;
        }
        mSpeechSynthesizer = synthesizer;

        // 流式发送完整文本
        Log.i(TAG, "TTS 合成: " + text);
        synthesizer.streamingCall(text);
        synthesizer.streamingComplete();
    }

    // ========== TTS 语音合成 ==========

    /**
     * 创建 DashScope TTS v2 合成器
     *
     * 配置：
     * - 模型: cosyvoice-v2
     * - 音色: longanyang（知性女声）
     * - 格式: PCM 22050Hz 单声道 16bit
     *
     * TTS 回调中将 PCM 音频帧写入 AudioTrack 实时播放，
     * 并计算振幅通过广播发送给 Launcher3 驱动嘴巴动画
     *
     * @return 配置好的 SpeechSynthesizer 实例，失败返回 null
     */
    private SpeechSynthesizer createTtsSynthesizer() {
        try {
            SpeechSynthesisParam ttsParam = SpeechSynthesisParam.builder()
                    .model("cosyvoice-v2")
                    .voice("longanyang")
                    .apiKey(DASHSCOPE_API_KEY)
                    .format(SpeechSynthesisAudioFormat.PCM_22050HZ_MONO_16BIT)
                    .build();

            SpeechSynthesizer synthesizer = new SpeechSynthesizer(
                    ttsParam,
                    new ResultCallback<SpeechSynthesisResult>() {

                        @Override
                        public void onEvent(SpeechSynthesisResult result) {
                            // 接收 TTS 合成的 PCM 音频帧
                            ByteBuffer audioFrame = result.getAudioFrame();
                            if (audioFrame == null || !audioFrame.hasRemaining()) {
                                return;
                            }

                            // 写入 AudioTrack 播放
                            AudioTrack track = mAudioTrack;
                            if (track != null && mState == VoiceState.SPEAKING) {
                                byte[] data = new byte[audioFrame.remaining()];
                                audioFrame.get(data);

                                // 计算振幅并发送给 Launcher3
                                float amplitude = calculateAmplitude(data);
                                sendTtsAmplitude(amplitude);

                                // 写入 AudioTrack（阻塞模式）
                                track.write(data, 0, data.length);
                            }
                        }

                        @Override
                        public void onComplete() {
                            Log.i(TAG, "TTS 合成播放完成");

                            // 回到 IDLE 状态（在状态机线程上执行）
                            mStateMachineHandler.post(() -> {
                                if (mState == VoiceState.SPEAKING) {
                                    // 等 AudioTrack 播放完缓冲区
                                    drainAudioTrack();
                                    stopSpeaking();
                                    transitionTo(VoiceState.IDLE);
                                    releaseWakeLock();
                                    updateNotification("AI 语音助手待命中");
                                    sendRobotEmotion("IDLE");

                                    // 重置振幅
                                    sendTtsAmplitude(0f);
                                }
                            });
                        }

                        @Override
                        public void onError(Exception e) {
                            Log.e(TAG, "TTS 合成出错", e);

                            mStateMachineHandler.post(() -> {
                                if (mState == VoiceState.SPEAKING) {
                                    stopSpeaking();
                                    transitionTo(VoiceState.IDLE);
                                    releaseWakeLock();
                                    updateNotification("TTS 合成出错");
                                    sendRobotEmotion("ERROR");
                                    sendTtsAmplitude(0f);
                                }
                            });
                        }
                    }
            );

            Log.i(TAG, "TTS 合成器创建成功");
            return synthesizer;

        } catch (Exception e) {
            Log.e(TAG, "创建 TTS 合成器失败", e);
            return null;
        }
    }

    // ========== AudioTrack 音频播放 ==========

    /**
     * 初始化 AudioTrack 播放器
     *
     * 配置：22050Hz / 单声道 / 16bit / 流模式
     * 使用 USAGE_ASSISTANT 类型，适合语音助手场景
     */
    private void initAudioTrack() {
        // 先释放旧的 AudioTrack
        releaseAudioTrack();

        int minBufferSize = AudioTrack.getMinBufferSize(
                TTS_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        // 使用 2 倍最小缓冲区，平衡延迟和稳定性
        int bufferSize = minBufferSize * 2;

        try {
            mAudioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(TTS_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            mAudioTrack.play();
            Log.i(TAG, "AudioTrack 初始化并开始播放，缓冲区: " + bufferSize + " bytes");

        } catch (Exception e) {
            Log.e(TAG, "初始化 AudioTrack 失败", e);
        }
    }

    /**
     * 等待 AudioTrack 播放完缓冲区中的剩余数据
     * 最多等待 3 秒，避免无限阻塞
     */
    private void drainAudioTrack() {
        AudioTrack track = mAudioTrack;
        if (track != null
                && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            try {
                // 使用 stop() 会等待缓冲区播放完
                track.stop();
                Log.d(TAG, "AudioTrack drain 完成");
            } catch (Exception e) {
                Log.e(TAG, "AudioTrack drain 失败", e);
            }
        }
    }

    /**
     * 释放 AudioTrack 资源
     */
    private void releaseAudioTrack() {
        if (mAudioTrack != null) {
            try {
                if (mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    mAudioTrack.stop();
                }
                mAudioTrack.release();
            } catch (Exception e) {
                Log.e(TAG, "释放 AudioTrack 失败", e);
            }
            mAudioTrack = null;
            Log.d(TAG, "AudioTrack 已释放");
        }
    }

    /**
     * 停止 TTS 合成和音频播放
     */
    private void stopSpeaking() {
        // 取消 TTS 合成
        SpeechSynthesizer synthesizer = mSpeechSynthesizer;
        if (synthesizer != null) {
            try {
                synthesizer.streamingCancel();
            } catch (Exception e) {
                Log.e(TAG, "取消 TTS 合成失败", e);
            }
            mSpeechSynthesizer = null;
        }

        // 释放 AudioTrack
        releaseAudioTrack();

        Log.d(TAG, "TTS 和播放已停止");
    }

    // ========== 音频振幅计算 ==========

    /**
     * 从 PCM 16bit 音频数据计算归一化振幅（0.0 ~ 1.0）
     * 用于发送给 Launcher3 驱动机器人嘴巴动画
     *
     * @param pcmData PCM 16bit little-endian 音频数据
     * @return 归一化振幅值（0.0 ~ 1.0）
     */
    private float calculateAmplitude(byte[] pcmData) {
        if (pcmData == null || pcmData.length < 2) {
            return 0f;
        }

        long sum = 0;
        int sampleCount = pcmData.length / 2;

        // 遍历 16bit PCM 采样值，计算 RMS（均方根）
        for (int i = 0; i < pcmData.length - 1; i += 2) {
            // little-endian: 低字节在前
            short sample = (short) ((pcmData[i] & 0xFF)
                    | (pcmData[i + 1] << 8));
            sum += (long) sample * sample;
        }

        // 计算 RMS 并归一化到 0.0 ~ 1.0
        double rms = Math.sqrt((double) sum / sampleCount);
        float amplitude = (float) (rms / Short.MAX_VALUE);

        // 限制范围
        return Math.min(1.0f, Math.max(0.0f, amplitude));
    }

    // ========== 跨进程 Broadcast ==========

    /**
     * 发送 AI 情绪状态给 Launcher3
     * Launcher3 根据情绪状态切换机器人动画
     *
     * @param emotion 情绪标识（IDLE/LISTENING/THINKING/SPEAKING/ERROR/HAPPY）
     */
    private void sendRobotEmotion(String emotion) {
        Intent intent = new Intent(ACTION_ROBOT_COMMAND);
        intent.putExtra(EXTRA_COMMAND_TYPE, "SET_AI_EMOTION");
        intent.putExtra(EXTRA_AI_EMOTION, emotion);
        sendBroadcast(intent);
        Log.d(TAG, "发送机器人情绪: " + emotion);
    }

    /**
     * 发送 TTS 振幅给 Launcher3
     * Launcher3 根据振幅大小驱动机器人嘴巴张合动画
     *
     * @param amplitude 振幅值（0.0 ~ 1.0）
     */
    private void sendTtsAmplitude(float amplitude) {
        Intent intent = new Intent(ACTION_ROBOT_COMMAND);
        intent.putExtra(EXTRA_COMMAND_TYPE, "SET_TTS_AMPLITUDE");
        intent.putExtra(EXTRA_TTS_AMPLITUDE, amplitude);
        sendBroadcast(intent);
    }

    // ========== 状态管理辅助方法 ==========

    /**
     * 状态转换
     * 记录日志并更新状态变量
     *
     * @param newState 目标状态
     */
    private void transitionTo(VoiceState newState) {
        VoiceState oldState = mState;
        mState = newState;
        Log.i(TAG, "状态转换: " + oldState + " → " + newState);
    }

    /**
     * 停止所有正在进行的活动
     * 录音、ASR、TTS、AudioTrack 全部停止并释放
     */
    private void stopAllActivities() {
        stopRecording();
        stopAsr();
        stopSpeaking();
    }

    /**
     * 获取 WakeLock
     * 防止语音交互过程中 CPU 进入休眠
     */
    private void acquireWakeLock() {
        if (mWakeLock != null && !mWakeLock.isHeld()) {
            // 最多持有 5 分钟，防止泄漏
            mWakeLock.acquire(5 * 60 * 1000L);
            Log.d(TAG, "WakeLock 已获取");
        }
    }

    /**
     * 释放 WakeLock
     */
    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            Log.d(TAG, "WakeLock 已释放");
        }
    }

    // ========== 通知管理 ==========

    /**
     * 创建前台服务通知渠道
     * Android 8.0+ 要求前台服务必须关联通知渠道
     */
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "AI 语音助手",
                NotificationManager.IMPORTANCE_LOW);  // LOW: 不发声、不弹出
        channel.setDescription("AI 语音助手后台运行通知");

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    /**
     * 构建前台服务通知
     *
     * @param text 通知正文内容
     * @return 构建好的通知对象
     */
    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AutoPilot")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
    }

    /**
     * 更新前台服务通知文本
     * 通过 NotificationManager 直接更新，无需重新 startForeground
     *
     * @param text 新的通知正文
     */
    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
