/*
 * BootReceiver — 开机广播接收器
 *
 * 职责：
 * 监听 BOOT_COMPLETED 和 LOCKED_BOOT_COMPLETED 广播，
 * 在系统启动完成后立即拉起 AutoPilotService 前台服务。
 *
 * 最终效果：
 * 设备开机后自动启动 WiFi 热点 + 双卡信号监控服务，
 * 无需用户手动操作。
 */
package com.autopilot.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.autopilot.service.AutoPilotService;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "AutoPilot.Boot";

    /**
     * 接收开机完成广播并启动核心服务
     *
     * @param context 应用上下文
     * @param intent  系统广播 Intent（BOOT_COMPLETED 或 LOCKED_BOOT_COMPLETED）
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            Log.i(TAG, "收到开机广播: " + action + "，启动 AutoPilotService");

            Intent serviceIntent = new Intent(context, AutoPilotService.class);
            context.startForegroundService(serviceIntent);
        }
    }
}
