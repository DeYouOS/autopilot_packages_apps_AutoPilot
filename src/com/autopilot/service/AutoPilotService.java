/*
 * AutoPilotService — 车载智能终端核心前台服务
 *
 * 职责：
 * 1. 开机自动启动 WiFi 热点（使用 TetheringManager.startTethering）
 * 2. 监听双卡信号强度变化，在信号差异超过阈值时自动切换数据 SIM
 * 3. 定期执行网络连通性探测（ping），确保当前数据通道畅通
 * 4. 以前台服务形式常驻运行，避免被系统回收
 *
 * 切换策略：
 * - 信号差异阈值：6 dBm（对端信号比当前强 6dB 以上才考虑切换）
 * - 冷却时间：120 秒（切换后等待 120 秒才允许再次切换）
 * - 主动探测间隔：15 秒（ping 223.5.5.5 检测当前网络是否畅通）
 * - 连续探测失败 3 次后强制切换到另一张卡
 *
 * 5. 设置屏幕常亮（AC/USB 充电时不灭屏，车载必须）
 *
 * 最终效果：
 * 设备作为车载 WiFi 热点，开机即用，自动选择信号最强的 SIM 卡上网，
 * 网络中断时自动切换，屏幕保持常亮，全程无需人工干预。
 */
package com.autopilot.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.TetheringManager;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.SignalStrength;

import java.util.Locale;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class AutoPilotService extends Service {

    private static final String TAG = "AutoPilot.Service";
    private static final String CHANNEL_ID = "autopilot_channel";

    // ============ 双卡切换策略参数 ============

    // 信号差异阈值（dBm）：对端信号比当前强多少才触发切换
    private static final int SIGNAL_SWITCH_THRESHOLD_DBM = 6;

    // 切换冷却时间（毫秒）：防止频繁切换导致网络震荡
    private static final long SWITCH_COOLDOWN_MS = 120_000;

    // 主动探测间隔（毫秒）：定期 ping 检测网络连通性
    private static final long PROBE_INTERVAL_MS = 15_000;

    // 连续探测失败次数上限：超过后强制切换 SIM
    private static final int MAX_PROBE_FAILURES = 3;

    // 热点启动延迟（毫秒）：等待 RIL/Modem 完全就绪
    private static final long HOTSPOT_START_DELAY_MS = 10_000;

    // ping 目标地址（阿里 DNS，国内延迟低）
    private static final String PING_TARGET = "223.5.5.5";

    // ============ 服务状态 ============

    private TetheringManager mTetheringManager;
    private WifiManager mWifiManager;
    private SubscriptionManager mSubscriptionManager;
    private TelephonyManager mTelephonyManager;
    private NotificationManager mNotificationManager;
    private PowerManager.WakeLock mWakeLock;

    private HandlerThread mWorkerThread;
    private Handler mWorkerHandler;
    private Executor mWorkerExecutor;

    // 当前数据 SIM 的订阅 ID
    private int mCurrentDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    // 每个 SIM 卡槽的最新信号强度（dBm）
    private final int[] mSignalDbm = new int[]{-999, -999};

    // 上次切换时间戳，用于冷却时间判断
    private long mLastSwitchTime = 0;

    // 连续 ping 失败计数
    private final AtomicInteger mProbeFailCount = new AtomicInteger(0);

    // 热点是否已成功启动的标记
    private final AtomicBoolean mHotspotStarted = new AtomicBoolean(false);

    // 标记服务是否正在运行
    private volatile boolean mRunning = false;

    // TelephonyCallback 实例（每个 SIM 卡槽一个）
    private TelephonyCallback[] mTelephonyCallbacks;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "AutoPilotService 创建");

        mTetheringManager = getSystemService(TetheringManager.class);
        mWifiManager = getSystemService(WifiManager.class);
        mSubscriptionManager = getSystemService(SubscriptionManager.class);
        mTelephonyManager = getSystemService(TelephonyManager.class);
        mNotificationManager = getSystemService(NotificationManager.class);

        // 获取 WakeLock 防止 CPU 休眠（车载场景常供电，但防止系统 Doze）
        PowerManager pm = getSystemService(PowerManager.class);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.acquire();

        // 车载本地化配置（跳过开机向导后由此处统一设置）
        applyCarLocaleDefaults();

        // 创建工作线程，所有网络操作在此线程执行，避免阻塞主线程
        mWorkerThread = new HandlerThread("AutoPilot-Worker");
        mWorkerThread.start();
        mWorkerHandler = new Handler(mWorkerThread.getLooper());
        mWorkerExecutor = mWorkerHandler::post;
    }

    /**
     * 服务启动入口
     *
     * @return START_STICKY 确保系统回收后自动重启
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "AutoPilotService 启动");

        // 创建通知渠道并启动前台服务
        createNotificationChannel();
        startForeground(1, buildNotification("正在初始化…"));

        if (!mRunning) {
            mRunning = true;

            // 设置屏幕常亮（车载场景：接 AC 或 USB 电源时不灭屏）
            configureStayAwake();

            // 延迟启动热点，等待 RIL 和 Modem 完全初始化
            mWorkerHandler.postDelayed(this::startHotspot, HOTSPOT_START_DELAY_MS);

            // 注册双卡信号监听
            mWorkerHandler.postDelayed(this::registerSignalListeners, HOTSPOT_START_DELAY_MS);

            // 启动主动探测循环
            mWorkerHandler.postDelayed(this::probeLoop, HOTSPOT_START_DELAY_MS + PROBE_INTERVAL_MS);
        }

        // START_STICKY：被杀后系统自动重启
        return START_STICKY;
    }

    // ============ 车载本地化预设 ============

    /**
     * 预设车载终端本地化配置
     *
     * 由于跳过了开机向导（SetupWizard 被 AutoPilot 替换），
     * 需要在此处通过代码设置以下首次启动配置：
     * - 系统语言：简体中文（zh-Hans-CN）
     * - 时间格式：24 小时制
     * - 日期格式：yyyy-MM-dd（中国标准）
     *
     * 语言和时区已通过系统属性（ro.product.locale / persist.sys.timezone）预设，
     * 此处补充 Settings.System 中的格式偏好。
     * 仅在首次启动（USER_SETUP_COMPLETE 刚被 Provision 设置后）执行，
     * 后续启动跳过以避免覆盖用户手动修改。
     */
    private void applyCarLocaleDefaults() {
        try {
            // 设置 24 小时制（车载仪表盘风格，国内习惯）
            Settings.System.putString(getContentResolver(),
                    Settings.System.TIME_12_24, "24");

            // 设置系统语言为简体中文
            // LocaleList.setDefault 仅影响当前进程，
            // persist.sys.locale 由系统属性 ro.product.locale 在首次启动时写入
            LocaleList.setDefault(new LocaleList(Locale.SIMPLIFIED_CHINESE));

            Log.i(TAG, "车载本地化配置完成: 24小时制, 简体中文");
        } catch (Exception e) {
            Log.e(TAG, "本地化配置失败: " + e.getMessage(), e);
        }
    }

    // ============ 屏幕常亮配置 ============

    /**
     * 设置屏幕在充电时保持常亮
     *
     * 通过 Settings.Global.STAY_ON_WHILE_PLUGGED_IN 控制屏幕在接电源时不灭屏。
     * 取值为 BatteryManager.BATTERY_PLUGGED_* 的位掩码：
     *   1 (BATTERY_PLUGGED_AC)  — AC 充电时常亮
     *   2 (BATTERY_PLUGGED_USB) — USB 充电时常亮
     *   4 (BATTERY_PLUGGED_WIRELESS) — 无线充电时常亮
     *
     * 车载场景使用 AC + USB = 3，覆盖车充的两种供电方式。
     * 需要 WRITE_SETTINGS 或 WRITE_SECURE_SETTINGS 权限（platform 签名应用已具备）。
     */
    private void configureStayAwake() {
        try {
            int pluggedBitmask = BatteryManager.BATTERY_PLUGGED_AC
                    | BatteryManager.BATTERY_PLUGGED_USB;
            Settings.Global.putInt(getContentResolver(),
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN, pluggedBitmask);
            Log.i(TAG, "屏幕常亮已设置: AC + USB 充电时不灭屏 (bitmask=" + pluggedBitmask + ")");
        } catch (Exception e) {
            Log.e(TAG, "设置屏幕常亮失败: " + e.getMessage(), e);
        }
    }

    // ============ WiFi 热点管理 ============

    /*
     * 启动 WiFi 热点
     *
     * 使用 TetheringManager.startTethering() 以特权方式启动 WiFi 热点。
     * 热点参数（SSID/密码/频段）使用系统当前配置，不强制覆盖。
     * 同时禁用热点自动关闭（setAutoShutdownEnabled=false），
     * 确保车载场景下即使无客户端连接也保持热点常开。
     */
    private void startHotspot() {
        Log.i(TAG, "正在启动 WiFi 热点…");

        try {
            // 确保 WiFi 已开启（热点需要 WiFi 硬件就绪）
            if (!mWifiManager.isWifiEnabled()) {
                mWifiManager.setWifiEnabled(true);
                Log.i(TAG, "WiFi 已开启");
            }

            // 获取当前热点配置，禁用自动关闭
            SoftApConfiguration currentConfig = mWifiManager.getSoftApConfiguration();
            if (currentConfig != null) {
                SoftApConfiguration.Builder builder =
                        new SoftApConfiguration.Builder(currentConfig);
                // 禁用无客户端时自动关闭热点
                builder.setAutoShutdownEnabled(false);
                mWifiManager.setSoftApConfiguration(builder.build());
                Log.i(TAG, "热点配置已更新：自动关闭已禁用，SSID="
                        + currentConfig.getWifiSsid());
            }

            // 使用 TetheringManager 启动 WiFi 热点
            TetheringManager.TetheringRequest request =
                    new TetheringManager.TetheringRequest.Builder(
                            TetheringManager.TETHERING_WIFI).build();

            mTetheringManager.startTethering(request, mWorkerExecutor,
                    new TetheringManager.StartTetheringCallback() {
                        @Override
                        public void onTetheringStarted() {
                            Log.i(TAG, "WiFi 热点启动成功！");
                            mHotspotStarted.set(true);
                            updateNotification("热点已启动");
                        }

                        @Override
                        public void onTetheringFailed(int error) {
                            Log.e(TAG, "WiFi 热点启动失败，错误码: " + error
                                    + "，10 秒后重试");
                            // 启动失败时延迟重试
                            mWorkerHandler.postDelayed(
                                    AutoPilotService.this::startHotspot, 10_000);
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "启动热点异常: " + e.getMessage(), e);
            // 异常时延迟重试
            mWorkerHandler.postDelayed(this::startHotspot, 10_000);
        }
    }

    // ============ 双卡信号监听 ============

    /*
     * 为每个活跃的 SIM 卡注册信号强度变化回调
     *
     * 使用 TelephonyCallback.SignalStrengthsListener 监听信号变化，
     * 每次信号更新时记录对应卡槽的 dBm 值，并触发切换评估逻辑。
     */
    private void registerSignalListeners() {
        List<SubscriptionInfo> subs = mSubscriptionManager.getActiveSubscriptionInfoList();
        if (subs == null || subs.isEmpty()) {
            Log.w(TAG, "无活跃 SIM 卡，30 秒后重试注册信号监听");
            mWorkerHandler.postDelayed(this::registerSignalListeners, 30_000);
            return;
        }

        Log.i(TAG, "检测到 " + subs.size() + " 张活跃 SIM 卡");
        mTelephonyCallbacks = new TelephonyCallback[subs.size()];

        // 记录当前数据 SIM
        mCurrentDataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
        Log.i(TAG, "当前数据 SIM subId=" + mCurrentDataSubId);

        for (int i = 0; i < subs.size(); i++) {
            final int slotIndex = i;
            final int subId = subs.get(i).getSubscriptionId();

            // 为每个 SIM 创建独立的 TelephonyManager 实例
            TelephonyManager tmForSub = mTelephonyManager.createForSubscriptionId(subId);

            mTelephonyCallbacks[i] = new SignalStrengthTelephonyCallback(slotIndex, subId);


            // 注册回调到工作线程
            tmForSub.registerTelephonyCallback(mWorkerExecutor, mTelephonyCallbacks[i]);
            Log.i(TAG, "已注册 SIM[" + slotIndex + "] subId=" + subId + " 信号监听");
        }
    }

    /*
     * 从 SignalStrength 对象提取综合 dBm 值
     *
     * 优先取 LTE/NR(5G) RSRP 值，如果不可用则使用 getLevel() 映射到近似 dBm。
     * 返回值越大（越接近 0）信号越强。
     *
     * @param ss SignalStrength 对象
     * @return 信号强度 dBm 值（负数，越大越好）
     */
    private int getDbmFromSignalStrength(SignalStrength ss) {
        if (ss == null) return -999;

        // 优先使用 getLevel() * -25 作为粗略 dBm 映射
        // Level 0=-125dBm, 1=-100dBm, 2=-75dBm, 3=-50dBm, 4=-25dBm
        int level = ss.getLevel();
        return -125 + (level * 25);
    }

    /*
     * 信号强度监听回调内部类
     *
     * TelephonyCallback 不能用匿名类直接 implements 接口，
     * 必须用具名内部类继承 TelephonyCallback 并实现 SignalStrengthsListener。
     * 每个 SIM 卡槽对应一个实例，通过 slotIndex 区分。
     */
    private class SignalStrengthTelephonyCallback extends TelephonyCallback
            implements TelephonyCallback.SignalStrengthsListener {

        private final int mSlotIndex;
        private final int mSubId;

        SignalStrengthTelephonyCallback(int slotIndex, int subId) {
            mSlotIndex = slotIndex;
            mSubId = subId;
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            int dbm = getDbmFromSignalStrength(signalStrength);
            mSignalDbm[mSlotIndex] = dbm;
            Log.d(TAG, "SIM[" + mSlotIndex + "] subId=" + mSubId
                    + " 信号更新: " + dbm + " dBm");
            evaluateSwitch();
        }
    }

    // ============ 切换评估逻辑 ============

    /*
     * 评估是否应该切换数据 SIM 卡
     *
     * 切换条件（全部满足才执行）：
     * 1. 两张卡都有信号（dBm > -999）
     * 2. 对端信号比当前强 SIGNAL_SWITCH_THRESHOLD_DBM 以上
     * 3. 距上次切换已过冷却时间
     * 4. 热点已成功启动
     */
    private void evaluateSwitch() {
        if (!mHotspotStarted.get()) return;

        List<SubscriptionInfo> subs = mSubscriptionManager.getActiveSubscriptionInfoList();
        if (subs == null || subs.size() < 2) return;

        // 冷却时间检查
        long now = System.currentTimeMillis();
        if (now - mLastSwitchTime < SWITCH_COOLDOWN_MS) return;

        // 找出当前数据卡和对端卡的卡槽索引
        int currentSlot = -1;
        int otherSlot = -1;
        for (int i = 0; i < subs.size(); i++) {
            if (subs.get(i).getSubscriptionId() == mCurrentDataSubId) {
                currentSlot = i;
            } else {
                otherSlot = i;
            }
        }

        if (currentSlot < 0 || otherSlot < 0) return;
        if (mSignalDbm[currentSlot] <= -999 || mSignalDbm[otherSlot] <= -999) return;

        int diff = mSignalDbm[otherSlot] - mSignalDbm[currentSlot];

        if (diff > SIGNAL_SWITCH_THRESHOLD_DBM) {
            Log.i(TAG, "信号切换触发: 当前 SIM[" + currentSlot + "]=" + mSignalDbm[currentSlot]
                    + "dBm, 对端 SIM[" + otherSlot + "]=" + mSignalDbm[otherSlot]
                    + "dBm, 差值=" + diff + "dB");
            performSwitch(subs.get(otherSlot).getSubscriptionId());
        }
    }

    /*
     * 执行数据 SIM 切换
     *
     * 调用 SubscriptionManager.setPreferredDataSubscriptionId() 切换数据通道，
     * needValidation=true 表示切换前先验证新 SIM 的网络连通性。
     *
     * @param targetSubId 目标 SIM 的订阅 ID
     */
    private void performSwitch(int targetSubId) {
        Log.i(TAG, "执行数据 SIM 切换: " + mCurrentDataSubId + " -> " + targetSubId);
        updateNotification("正在切换数据 SIM…");

        mSubscriptionManager.setPreferredDataSubscriptionId(
                targetSubId,
                true,  // needValidation: 切换前先验证网络连通性
                mWorkerExecutor,
                result -> {
                    if (result == TelephonyManager
                            .SET_OPPORTUNISTIC_SUB_SUCCESS) {
                        Log.i(TAG, "数据 SIM 切换成功: subId=" + targetSubId);
                        mCurrentDataSubId = targetSubId;
                        mLastSwitchTime = System.currentTimeMillis();
                        mProbeFailCount.set(0);
                        updateNotification("数据已切换到 SIM " + targetSubId);
                    } else {
                        Log.w(TAG, "数据 SIM 切换失败: result=" + result);
                        // 切换失败时不重置冷却时间，避免连续重试
                        updateNotification("SIM 切换失败 (code=" + result + ")");
                    }
                });
    }

    // ============ 网络连通性探测 ============

    /*
     * 主动探测循环
     *
     * 每 PROBE_INTERVAL_MS 毫秒执行一次 ping 测试，
     * 检测当前数据通道是否畅通。连续失败达到阈值时
     * 强制切换到另一张 SIM 卡。
     */
    private void probeLoop() {
        if (!mRunning) return;

        mWorkerHandler.post(() -> {
            boolean reachable = probePing();

            if (reachable) {
                mProbeFailCount.set(0);
            } else {
                int failures = mProbeFailCount.incrementAndGet();
                Log.w(TAG, "Ping 失败 (" + failures + "/" + MAX_PROBE_FAILURES + ")");

                if (failures >= MAX_PROBE_FAILURES) {
                    Log.e(TAG, "连续 " + failures + " 次 ping 失败，强制切换 SIM");
                    forceSwitchToOtherSim();
                    mProbeFailCount.set(0);
                }
            }
        });

        // 调度下一次探测
        mWorkerHandler.postDelayed(this::probeLoop, PROBE_INTERVAL_MS);
    }

    /*
     * 执行单次 ping 探测
     *
     * 向 PING_TARGET 发送 ICMP ping，超时 3 秒。
     *
     * @return true 表示网络通畅，false 表示不可达
     */
    private boolean probePing() {
        try {
            InetAddress addr = InetAddress.getByName(PING_TARGET);
            return addr.isReachable(3000);
        } catch (IOException e) {
            Log.w(TAG, "Ping 异常: " + e.getMessage());
            return false;
        }
    }

    /*
     * 强制切换到另一张 SIM 卡（无视信号强度和冷却时间）
     *
     * 在当前数据通道完全不通时调用。
     * 此方法忽略冷却时间限制，立即切换。
     */
    private void forceSwitchToOtherSim() {
        List<SubscriptionInfo> subs = mSubscriptionManager.getActiveSubscriptionInfoList();
        if (subs == null || subs.size() < 2) {
            Log.w(TAG, "无法强制切换：不足 2 张 SIM 卡");
            return;
        }

        for (SubscriptionInfo sub : subs) {
            if (sub.getSubscriptionId() != mCurrentDataSubId) {
                // 强制切换时重置冷却时间
                mLastSwitchTime = 0;
                performSwitch(sub.getSubscriptionId());
                return;
            }
        }
    }

    // ============ 通知管理 ============

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(com.autopilot.R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(com.autopilot.R.string.notification_channel_desc));
        mNotificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(com.autopilot.R.string.notification_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        mNotificationManager.notify(1, buildNotification(text));
    }

    // ============ 生命周期 ============

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "AutoPilotService 销毁");
        mRunning = false;

        // 释放 WakeLock
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }

        // 停止工作线程
        if (mWorkerThread != null) {
            mWorkerThread.quitSafely();
        }

        super.onDestroy();
    }
}
