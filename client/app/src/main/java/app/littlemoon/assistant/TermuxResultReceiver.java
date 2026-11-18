package app.littlemoon.assistant;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/** Receives the one-shot callback even if the chat Activity has been recreated. */
public final class TermuxResultReceiver extends BroadcastReceiver {
    static final String STORE = "local_tool_execution";
    static final String REQUEST_ID = "request_id";
    static final String STARTED = "started";
    static final String TIMEOUT_SHOWN = "timeout_shown";
    static final String RESULT = "result_text";
    static final String ACTION = "app.littlemoon.assistant.TERMUX_RESULT";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent.getData() == null || !"assistant-tool".equals(intent.getData().getScheme()) ||
                !context.getPackageName().equals(intent.getData().getAuthority())) return;
        String id = intent.getData().getLastPathSegment();
        if (id == null) return;
        if (RemoteTermux.RESULT_ACTION.equals(intent.getAction())) {
            final PendingResult pending = goAsync();
            final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
            final Runnable done = () -> { if (finished.compareAndSet(false, true)) pending.finish(); };
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(done, 20_000);
            RemoteTermux.onResult(context.getApplicationContext(), id, intent.getBundleExtra("result"), done);
            return;
        }
        if (!ACTION.equals(intent.getAction())) return;
        SharedPreferences store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        if (!id.equals(store.getString(REQUEST_ID, null))) return;

        String message = formatResult(intent.getBundleExtra("result"));
        store.edit().remove(REQUEST_ID).remove(STARTED).remove(TIMEOUT_SHOWN)
                .putString(RESULT, message).apply();
    }

    static String formatResult(Bundle result) {
        if (result == null) return "Termux 已回调，但没有返回执行结果。";
        if (result.getInt("err", Activity.RESULT_OK) != Activity.RESULT_OK) {
            return "Termux 无法执行这项检查。" + excerpt(result.getString("errmsg", ""));
        }
        if (!result.containsKey("exitCode")) return "Termux 没有返回退出码，结果未知。";
        int exit = result.getInt("exitCode");
        String stdout = result.getString("stdout", "");
        String stderr = result.getString("stderr", "");
        if (exit == 0) {
            String version = stdout.trim().isEmpty() ? stderr : stdout;
            return "本机 Python 检查完成（退出码 0）。" + excerpt(version);
        }
        return "本机 Python 检查失败（退出码 " + exit + "）。" + excerpt(stderr)
                + excerpt(stdout);
    }

    private static String excerpt(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String text = value.trim();
        return "\n" + (text.length() > 500 ? text.substring(0, 500) + "…" : text);
    }
}
