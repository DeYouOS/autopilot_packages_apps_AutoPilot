/*
 * ProvisionActivity — 静默设备配置完成器
 *
 * 职责：
 * 替代 AOSP SetupWizard / Provision，在首次启动时自动完成设备配置。
 * 设置 Settings.Global.DEVICE_PROVISIONED=1 和 Settings.Secure.USER_SETUP_COMPLETE=1，
 * 然后禁用自身组件并跳转到 Launcher，整个过程对用户完全透明。
 *
 * 工作原理：
 * 1. 注册为 HOME + SETUP_WIZARD category 的 Activity（与 Provision 相同）
 * 2. 系统首次启动时，因 device_provisioned=0 而自动启动本 Activity
 * 3. 立即设置 provisioning 标志，让系统进入正常运行模式
 * 4. 禁用自身组件，后续启动不再出现
 * 5. 结束 Activity，系统自动进入 Launcher3 桌面
 *
 * 最终效果：
 * 开机无任何向导界面，直接进入桌面，语言/时区由系统属性预设。
 */
package com.autopilot;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

public class ProvisionActivity extends Activity {

    private static final String TAG = "AutoPilot.Provision";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "开始静默设备配置…");

        // 标记设备已完成初始配置（等同于 SetupWizard 完成后的设置）
        Settings.Global.putInt(getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 1);
        Settings.Secure.putInt(getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 1);
        Log.i(TAG, "device_provisioned=1, user_setup_complete=1 已设置");

        // 禁用本 Activity 组件，避免后续启动时再次出现
        // 与 AOSP Provision 行为一致：完成配置后从 PackageManager 中移除
        PackageManager pm = getPackageManager();
        ComponentName self = new ComponentName(this, ProvisionActivity.class);
        pm.setComponentEnabledSetting(self,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        Log.i(TAG, "ProvisionActivity 已禁用，后续启动不再触发");

        // 结束 Activity，系统将自动启动 Launcher3 作为 HOME
        finish();
    }
}
