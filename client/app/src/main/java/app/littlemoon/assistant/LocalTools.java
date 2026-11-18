package app.littlemoon.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** Tools invoked explicitly from the visible assistant UI. None uses the chat server. */
final class LocalTools {
    private static final int PYTHON_PERMISSION = 6101;
    private static final int LOCATION_PERMISSION = 6102;
    private static final int PICK_AUDIO = 6103;
    private static final long TERMUX_TIMEOUT_MS = 45_000;
    private static final long LOCATION_TIMEOUT_MS = 30_000;

    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences executions;
    private final LocationManager locationManager;
    private LocationListener locationRequest;
    private final Runnable locationTimeout = () -> finishLocation("暂未收到位置。请确认系统定位已开启，再试一次。");
    private final Runnable readResult = this::readTermuxResult;
    private final SharedPreferences.OnSharedPreferenceChangeListener resultListener = (prefs, key) -> {
        if (TermuxResultReceiver.RESULT.equals(key)) handler.post(readResult);
    };

    LocalTools(MainActivity activity) {
        this.activity = activity;
        this.executions = activity.getSharedPreferences(TermuxResultReceiver.STORE, Context.MODE_PRIVATE);
        this.locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
    }

    void onResume() {
        executions.registerOnSharedPreferenceChangeListener(resultListener);
        readTermuxResult();
    }

    void onPause() {
        executions.unregisterOnSharedPreferenceChangeListener(resultListener);
        handler.removeCallbacks(readResult);
        if (locationRequest != null) finishLocation("已离开助手，本次定位已取消。");
    }

    void onPermissionResult(int requestCode) {
        if (requestCode == PYTHON_PERMISSION) {
            if (granted(TermuxCommands.RUN_PERMISSION)) runPython();
            else activity.showToolResult("要运行本机脚本，请在系统设置的本应用“其他权限”中允许“在 Termux 环境中运行命令”。");
        } else if (requestCode == LOCATION_PERMISSION) {
            if (hasLocationPermission()) startLocation();
            else activity.showToolResult("未取得定位权限。可在系统设置中允许助手仅使用期间访问位置。");
        }
    }

    void checkPython() {
        if (!TermuxCommands.available(activity)) {
            activity.showToolResult("未找到支持 RUN_COMMAND 的 Termux。安装并打开 Termux，完成初始化后再试。");
            return;
        }
        if (!granted(TermuxCommands.RUN_PERMISSION)) {
            activity.requestPermissions(new String[]{TermuxCommands.RUN_PERMISSION}, PYTHON_PERMISSION);
            return;
        }
        runPython();
    }

    private void runPython() {
        readTermuxResult();
        if (executions.contains(TermuxResultReceiver.REQUEST_ID)
                && !executions.getBoolean(TermuxResultReceiver.TIMEOUT_SHOWN, false)) {
            activity.showToolResult("Python 检查正在运行，正在等待 Termux 回传结果。");
            return;
        }
        String id = UUID.randomUUID().toString();
        PendingIntent reply = TermuxCommands.callback(activity, TermuxResultReceiver.ACTION, id);
        boolean saved = executions.edit().putString(TermuxResultReceiver.REQUEST_ID, id)
                .putLong(TermuxResultReceiver.STARTED, System.currentTimeMillis())
                .remove(TermuxResultReceiver.TIMEOUT_SHOWN).commit();
        if (!saved) {
            reply.cancel();
            activity.showToolResult("无法保存本次调用，请检查手机可用空间。");
            return;
        }
        try {
            TermuxCommands.pythonVersion(activity, reply);
            activity.showToolResult("已请求 Termux 检查本机 Python 版本。");
            readTermuxResult();
        } catch (SecurityException | IllegalStateException e) {
            reply.cancel();
            executions.edit().remove(TermuxResultReceiver.REQUEST_ID)
                    .remove(TermuxResultReceiver.STARTED).apply();
            activity.showToolResult("未能启动 Termux。请确认助手已获执行权限，并在 Termux 中开启 allow-external-apps 后重试。");
        }
    }

    private void readTermuxResult() {
        handler.removeCallbacks(readResult);
        String result = executions.getString(TermuxResultReceiver.RESULT, null);
        if (result != null) {
            executions.edit().remove(TermuxResultReceiver.RESULT).apply();
            activity.showToolResult(result);
        }
        if (!executions.contains(TermuxResultReceiver.REQUEST_ID)
                || executions.getBoolean(TermuxResultReceiver.TIMEOUT_SHOWN, false)) return;
        long elapsed = System.currentTimeMillis() - executions.getLong(TermuxResultReceiver.STARTED, 0);
        if (elapsed >= 0 && elapsed < TERMUX_TIMEOUT_MS) {
            handler.postDelayed(readResult, TERMUX_TIMEOUT_MS - elapsed);
        } else {
            executions.edit().putBoolean(TermuxResultReceiver.TIMEOUT_SHOWN, true).apply();
            activity.showToolResult("尚未收到 Termux 的结果，执行状态未知。请确认已安装 Python 并开启 allow-external-apps。");
        }
    }

    void playMusic() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        try {
            activity.startActivityForResult(pick, PICK_AUDIO);
        } catch (ActivityNotFoundException e) {
            activity.showToolResult("设备没有可选择音频的文件管理器。");
        }
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_AUDIO || resultCode != Activity.RESULT_OK || data == null) return;
        Uri content = data.getData();
        if (content == null || !"content".equals(content.getScheme())) return;
        try {
            String type = activity.getContentResolver().getType(content);
            Intent play = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(content, type != null && type.startsWith("audio/") ? type : "audio/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(play);
            activity.showToolResult("已将选中的音频交给播放器。");
        } catch (ActivityNotFoundException | SecurityException e) {
            activity.showToolResult("无法打开这段音频。请检查是否已安装支持此格式的播放器。");
        }
    }

    void locateOnce() {
        if (!hasLocationPermission()) {
            activity.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION);
            return;
        }
        startLocation();
    }

    private boolean granted(String permission) {
        return activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return granted(Manifest.permission.ACCESS_COARSE_LOCATION)
                || granted(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private void startLocation() {
        if (locationRequest != null) return;
        if (locationManager == null) {
            activity.showToolResult("系统未提供定位服务。");
            return;
        }
        locationRequest = new LocationListener() {
            @Override public void onLocationChanged(Location value) {
                if (this != locationRequest || value == null) return;
                long age = SystemClock.elapsedRealtimeNanos() - value.getElapsedRealtimeNanos();
                if (age < 0 || age > 60_000_000_000L) return;
                String time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(value.getTime()));
                String accuracy = value.hasAccuracy()
                        ? String.format(Locale.ROOT, " · 精度约 %.0f 米", value.getAccuracy()) : "";
                finishLocation(String.format(Locale.ROOT, "本机位置  %.5f, %.5f\n%s%s",
                        value.getLatitude(), value.getLongitude(), time, accuracy));
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        boolean listening = listenForLocation(LocationManager.NETWORK_PROVIDER);
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            listening = listenForLocation(LocationManager.GPS_PROVIDER) || listening;
        }
        if (listening) {
            handler.postDelayed(locationTimeout, LOCATION_TIMEOUT_MS);
            activity.showToolResult("正在获取一次本机位置，离开助手会取消。");
        } else {
            finishLocation("当前没有可用定位源。请检查系统定位开关与助手的定位权限。");
        }
    }

    private boolean listenForLocation(String provider) {
        try {
            if (!locationManager.isProviderEnabled(provider)) return false;
            locationManager.requestLocationUpdates(provider, 1000L, 0f, locationRequest, Looper.getMainLooper());
            return true;
        } catch (IllegalArgumentException | SecurityException e) {
            return false;
        }
    }

    private void finishLocation(String message) {
        handler.removeCallbacks(locationTimeout);
        if (locationRequest != null) {
            try {
                locationManager.removeUpdates(locationRequest);
            } catch (SecurityException ignored) {
                // Permission may be revoked while waiting.
            }
        }
        locationRequest = null;
        activity.showToolResult(message);
    }
}
