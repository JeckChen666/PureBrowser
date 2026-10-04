package com.example.purebrowser.share;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Test APK only. Own-UID opt-in control; no incoming URI/Intent is used or forwarded. */
public final class Code13ShareControlActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(24, 200, 24, 24);
        TextView status = new TextView(this);
        status.setText("code13 测试接收器控制：不授予文件权限。请从正式包的系统分享选择器发起。");
        Button enable = new Button(this);
        enable.setText("启用 code13 测试接收器");
        enable.setOnClickListener(v -> configure(true, status));
        Button disable = new Button(this);
        disable.setText("停用 code13 测试接收器");
        disable.setOnClickListener(v -> configure(false, status));
        view.addView(status); view.addView(enable); view.addView(disable);
        setContentView(view);
    }
    private void configure(boolean enabled, TextView status) {
        getPackageManager().setComponentEnabledSetting(
            new ComponentName(this, Code13ShareReceiver.class),
            enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP);
        status.setText(enabled ? "接收器已启用，请返回正式 code13 从原生分享按钮选择。" : "接收器已停用。");
    }
}
