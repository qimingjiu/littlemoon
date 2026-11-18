package app.littlemoon.assistant;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Durable, account-bound processing for the fixed tools offered to the server. */
final class RemoteTermux {
    static final String RESULT_ACTION = "app.littlemoon.assistant.REMOTE_TERMUX_RESULT";
    private static final int UPLOAD_JOB = 404;
    private static final long TIMEOUT_MS = 45_000;
    private static final long KEEP_MS = 24 * 60 * 60 * 1000L;
    private static final long MAX_PUSH_AGE_MS = 5 * 60 * 1000L;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    private static final Pattern VERSION = Pattern.compile("Python [0-9][0-9A-Za-z.+-]{0,62}");

    private RemoteTermux() {}

    static synchronized void accept(Context context, Map<String, String> data, long sentAt) {
        String chat = data.get("conversation_id");
        String request = data.get("request_id");
        String owner = AssistantApi.scope(context);
        long now = System.currentTimeMillis();
        if (owner.isEmpty() || !validId(chat) || !validId(request) ||
                (sentAt > 0 && now - sentAt > MAX_PUSH_AGE_MS)) return;

        // Bind each request to the paired device so a delayed push cannot cross a re-pair.
        String device = data.get("device_id");
        if (device == null || !device.equals(owner.substring(owner.lastIndexOf('\n') + 1))) return;
        String execution = UUID.nameUUIDFromBytes((owner + '\0' + chat + '\0' + request)
                .getBytes(StandardCharsets.UTF_8)).toString();
        SharedPreferences records = records(context);
        purge(context, records, now);
        if (records.contains(execution)) {
            JSONObject existing = read(records, execution);
            if (existing != null && !existing.optBoolean("sent")) schedule(context);
            return;
        }
        if (records.getAll().size() >= 128) return;

        JSONObject entry = new JSONObject();
        put(entry, "owner", owner);
        put(entry, "chat", chat);
        put(entry, "request", request);
        put(entry, "created", now);
        if (!records.edit().putString(execution, entry.toString()).commit()) return;
        if (!"python_version".equals(data.get("tool")) || !emptyArgs(data.get("args"))) {
            complete(context, execution, result("unsupported", "unsupported_tool_or_arguments"));
            return;
        }
        if (!TermuxCommands.available(context)) {
            complete(context, execution, result("blocked", "termux_unavailable"));
            return;
        }
        if (context.checkSelfPermission(TermuxCommands.RUN_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            complete(context, execution, result("blocked", "termux_permission_required"));
            return;
        }

        // Dispatch while the push callback is active. Android and the ROM enforce service-start rules.
        PendingIntent callback = TermuxCommands.callback(context, RESULT_ACTION, execution);
        try {
            TermuxCommands.pythonVersion(context, callback);
            schedule(context);
        } catch (IllegalStateException | SecurityException e) {
            callback.cancel();
            complete(context, execution, result("blocked", "termux_start_blocked"));
        }
    }

    static void onResult(Context context, String execution, Bundle bundle) { onResult(context, execution, bundle, null); }

    static void onResult(Context context, String execution, Bundle bundle, Runnable done) {
        JSONObject result;
        if (bundle == null || bundle.getInt("err", -1) != -1 || !bundle.containsKey("exitCode")) {
            result = result("failed", "termux_execution_failed");
        } else {
            int exitCode = bundle.getInt("exitCode");
            String stdout = bundle.getString("stdout", "").trim();
            String version = stdout.isEmpty() ? bundle.getString("stderr", "").trim() : stdout;
            result = exitCode == 0 && VERSION.matcher(version).matches() ? result("ok", null) :
                    result("failed", exitCode == 0 ? "unexpected_version_output" : "python_exited");
            if ("ok".equals(result.optString("status"))) put(result, "output", version);
            put(result, "exit_code", exitCode);
        }
        complete(context, execution, result, done);
    }

    private static void complete(Context context, String execution, JSONObject result) {
        complete(context, execution, result, null);
    }

    /** Stores the result, keeps the JobScheduler upload as fallback, then tries to upload right away. */
    private static void complete(Context context, String execution, JSONObject result, Runnable done) {
        boolean stored;
        synchronized (RemoteTermux.class) {
            SharedPreferences records = records(context);
            JSONObject entry = read(records, execution);
            stored = entry != null && !entry.has("result") && !entry.optBoolean("sent");
            if (stored) {
                put(entry, "result", result);
                stored = records.edit().putString(execution, entry.toString()).commit();
                if (stored) schedule(context);
            }
        }
        if (stored) uploadNow(context, execution, done);
        else if (done != null) done.run();
    }

    private static void uploadNow(Context context, String execution, Runnable done) {
        JSONObject entry;
        synchronized (RemoteTermux.class) { entry = read(records(context), execution); }
        if (entry == null || entry.optBoolean("sent") || !entry.has("result")) {
            if (done != null) done.run();
            return;
        }
        put(entry, "execution", execution);
        try {
            new AssistantApi(context).uploadRemoteTermuxResult(entry, retry -> {
                if (!retry) delivered(context, execution);
                if (done != null) done.run();
            });
        } catch (RuntimeException e) {
            Log.w("LittleMoon", "direct Termux result upload failed; job will retry", e);
            if (done != null) done.run();
        }
    }

    static synchronized void schedule(Context context) {
        SharedPreferences records = records(context);
        long now = System.currentTimeMillis();
        purge(context, records, now);
        long earliest = Long.MAX_VALUE;
        for (String key : records.getAll().keySet()) {
            JSONObject entry = read(records, key);
            if (entry == null || entry.optBoolean("sent")) continue;
            earliest = Math.min(earliest, entry.has("result") ? now : entry.optLong("created") + TIMEOUT_MS);
        }
        if (earliest == Long.MAX_VALUE) return;
        JobInfo job = new JobInfo.Builder(UPLOAD_JOB, new ComponentName(context, RemoteTermuxUploadJob.class))
                .setMinimumLatency(Math.max(0, earliest - now))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) Log.w("LittleMoon", "Termux result job not scheduled");
    }

    static synchronized JSONObject nextResult(Context context) {
        SharedPreferences records = records(context);
        long now = System.currentTimeMillis();
        purge(context, records, now);
        for (String key : records.getAll().keySet()) {
            JSONObject entry = read(records, key);
            if (entry == null || entry.optBoolean("sent")) continue;
            if (!entry.has("result")) {
                if (now < entry.optLong("created") + TIMEOUT_MS) continue;
                put(entry, "result", result("timeout", "termux_callback_timeout"));
                if (!records.edit().putString(key, entry.toString()).commit()) continue;
            }
            put(entry, "execution", key);
            return entry;
        }
        return null;
    }

    static synchronized void delivered(Context context, String execution) {
        SharedPreferences records = records(context);
        JSONObject entry = read(records, execution);
        if (entry == null) return;
        entry.remove("result");
        put(entry, "sent", true);
        records.edit().putString(execution, entry.toString()).commit();
    }

    private static void purge(Context context, SharedPreferences records, long now) {
        String owner = AssistantApi.scope(context);
        SharedPreferences.Editor editor = null;
        for (String key : records.getAll().keySet()) {
            JSONObject entry = read(records, key);
            if (entry != null && owner.equals(entry.optString("owner")) &&
                    now - entry.optLong("created") <= KEEP_MS) continue;
            if (editor == null) editor = records.edit();
            editor.remove(key);
        }
        if (editor != null) editor.commit();
    }

    private static boolean validId(String value) { return value != null && ID.matcher(value).matches(); }
    private static boolean emptyArgs(String args) {
        if (args == null) return true;
        try { return args.length() <= 64 && new JSONObject(args).length() == 0; }
        catch (JSONException e) { return false; }
    }
    private static SharedPreferences records(Context context) {
        return context.getSharedPreferences("remote_termux_requests", Context.MODE_PRIVATE);
    }
    private static JSONObject read(SharedPreferences records, String key) {
        try {
            String value = records.getString(key, null);
            return value == null ? null : new JSONObject(value);
        } catch (JSONException e) { return null; }
    }
    private static JSONObject result(String status, String reason) {
        JSONObject result = new JSONObject();
        put(result, "status", status);
        if (reason != null) put(result, "reason", reason);
        return result;
    }
    private static void put(JSONObject json, String key, Object value) {
        try { json.put(key, value); }
        catch (JSONException e) { throw new IllegalArgumentException(key, e); }
    }
}
