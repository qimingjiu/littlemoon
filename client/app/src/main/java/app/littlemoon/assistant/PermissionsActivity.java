package app.littlemoon.assistant;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class PermissionsActivity extends Activity {
    private static final String COLOR_OS_SAFECENTER = "com.coloros.safecenter";
    private static final int TERMUX_PERMISSION = 6111;
    private static final int GREEN = Color.rgb(40, 85, 66);
    private static final int GRAY = Color.rgb(80, 100, 88);
    private static final int AMBER = Color.rgb(128, 77, 30);

    private LinearLayout rows;
    private TextView notifications;
    private TextView battery;
    private TextView termux;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.assistant_permissions);
        configureInsets();
        findViewById(R.id.permissions_back).setOnClickListener(view -> finish());
        rows = findViewById(R.id.permission_rows);
        notifications = addRow("通知", "用于锁屏和后台收到聊天回复。", this::openNotificationSettings);
        battery = addRow("忽略电池优化", "可减少系统省电对后台工具运行的限制。", this::openBatterySettings);
        if (isColorOs()) {
            TextView startup = addRow("ColorOS 自启动与关联启动",
                    "在启动管理中找到小月助手，打开“自启动”和“关联启动”。若未找到入口，可在系统设置中搜索。",
                    this::openColorOsStartup);
            showState(startup, "请到系统设置确认", GRAY);
        }
        termux = addRow("Termux 命令权限",
                "配对服务器可在后台检查本机 Python 版本，仅收到版本或执行状态。"
                        + "\n在 Termux 中将 allow-external-apps 设为 true，保存后运行 termux-reload-settings。",
                this::openTermuxSettings);
        TextView doNotDisturb = addRow("勿扰权限", "调整媒体音量无需此授权。", null);
        showState(doNotDisturb, "本版不需要", GRAY);
        TextView exactAlarms = addRow("精确闹钟", "延时请求由服务器安排。", null);
        showState(exactAlarms, "本版不需要", GRAY);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(PushApplication.REPLIES);
        if (!manager.areNotificationsEnabled()) {
            showState(notifications, "系统通知已关闭", AMBER);
        } else if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            showState(notifications, "聊天回复频道已关闭", AMBER);
        } else {
            showState(notifications, "已开启", GREEN);
        }

        boolean unrestricted = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        showState(battery, unrestricted ? "已忽略电池优化" : "受电池优化限制", unrestricted ? GREEN : GRAY);

        if (!TermuxCommands.available(this)) {
            showState(termux, "未检测到支持 RUN_COMMAND 的 Termux", GRAY);
        } else if (checkSelfPermission(TermuxCommands.RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            showState(termux, "已授权给小月助手", GREEN);
        } else {
            showState(termux, "尚未授权给小月助手", AMBER);
        }
    }

    private void openNotificationSettings() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(PushApplication.REPLIES);
        if (manager.areNotificationsEnabled() && channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE
                && tryOpen(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, channel.getId()))) return;
        if (!tryOpen(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))) openAppDetails();
    }

    private void openBatterySettings() {
        boolean unrestricted = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        if (!unrestricted && tryOpen(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri()))) return;
        if (!tryOpen(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppDetails();
    }

    private boolean isColorOs() {
        return "oppo".equals(Build.MANUFACTURER.toLowerCase(Locale.ROOT)) || systemColorOsPackage();
    }

    private boolean systemColorOsPackage() {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(COLOR_OS_SAFECENTER, 0);
            return (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openColorOsStartup() {
        if (systemColorOsPackage()) {
            for (String suffix : new String[]{".startupapp.StartupAppListActivity", ".permission.startup.StartupAppListActivity"}) {
                ComponentName component = new ComponentName(COLOR_OS_SAFECENTER, COLOR_OS_SAFECENTER + suffix);
                try {
                    ActivityInfo info = getPackageManager().getActivityInfo(component, 0);
                    if (info.exported && info.enabled && tryOpen(new Intent().setComponent(component))) return;
                } catch (PackageManager.NameNotFoundException ignored) {
                    // ColorOS versions expose different activities.
                }
            }
        }
        openAppDetails();
    }

    private void openTermuxSettings() {
        if (TermuxCommands.available(this)
                && checkSelfPermission(TermuxCommands.RUN_PERMISSION) != PackageManager.PERMISSION_GRANTED
                && (!getPreferences(MODE_PRIVATE).getBoolean("askedForTermux", false)
                || shouldShowRequestPermissionRationale(TermuxCommands.RUN_PERMISSION))) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedForTermux", true).apply();
            requestPermissions(new String[]{TermuxCommands.RUN_PERMISSION}, TERMUX_PERMISSION);
            return;
        }
        openAppDetails();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == TERMUX_PERMISSION) refresh();
    }

    private Uri appUri() {
        return Uri.fromParts("package", getPackageName(), null);
    }

    private void openAppDetails() {
        if (!tryOpen(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri()))) {
            Toast.makeText(this, "请在系统设置中打开小月助手的应用信息。", Toast.LENGTH_LONG).show();
        }
    }

    private boolean tryOpen(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private TextView addRow(String label, String explanation, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable card = new GradientDrawable();
        card.setColor(Color.WHITE);
        card.setCornerRadius(dp(14));
        row.setBackground(card);
        TextView title = caption(label, 16, Color.rgb(27, 48, 43));
        title.setTypeface(null, Typeface.BOLD);
        row.addView(title);
        TextView status = caption("", 14, GRAY);
        status.setPadding(0, dp(5), 0, dp(4));
        row.addView(status);
        row.addView(caption(explanation, 13, GRAY));
        if (action != null) {
            row.setFocusable(true);
            row.setOnClickListener(view -> action.run());
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x20285542), card, null));
            TextView link = caption("打开设置", 13, GREEN);
            link.setPadding(0, dp(9), 0, 0);
            row.addView(link);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10);
        rows.addView(row, params);
        return status;
    }

    private TextView caption(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void showState(TextView status, String text, int color) {
        status.setText(text);
        status.setTextColor(color);
    }

    private void configureInsets() {
        View page = findViewById(R.id.permission_page);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(0xFFF7F8F4);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            page.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return windowInsets;
            });
            page.requestApplyInsets();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
