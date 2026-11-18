package app.littlemoon.assistant;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

final class TermuxCommands {
    static final String RUN_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final ComponentName SERVICE = new ComponentName("com.termux", "com.termux.app.RunCommandService");

    private TermuxCommands() {}

    static boolean available(Context context) {
        try {
            return context.getPackageManager().getServiceInfo(SERVICE, 0).exported;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static PendingIntent callback(Context context, String action, String id) {
        Intent callback = new Intent(context, TermuxResultReceiver.class).setAction(action)
                .setData(new Uri.Builder().scheme("assistant-tool")
                        .authority(context.getPackageName()).appendPath(id).build());
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(context, 0, callback, flags);
    }

    static void pythonVersion(Context context, PendingIntent callback) {
        Intent command = new Intent("com.termux.RUN_COMMAND").setComponent(SERVICE)
                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/python")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"--version"})
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback);
        // Termux promotes its service on creation. The target UID may currently be idle.
        if (context.startForegroundService(command) == null) throw new IllegalStateException("Termux unavailable");
    }
}
