package app.littlemoon.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

public final class AssistantApi {
    public interface Callback { void done(String error); }
    private interface Work { void run() throws Exception; }
    interface RemoteResultCallback { void done(boolean retry); }
    private static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) {
            super(status == 401 || status == 403 ? "服务器拒绝授权，请检查配对（HTTP "+status+"）" : "服务器返回 HTTP "+status);
            this.status = status;
        }
    }
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String PREFS = "assistant.server";
    private static final String KEY = "assistant-device-v1";
    private final Context context;
    private final SharedPreferences prefs;
    private final ChatStore store;
    private volatile boolean closed;

    public AssistantApi(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        store = new ChatStore(this.context);
    }
    public boolean paired() { return !prefs.getString("credential","").isEmpty(); }
    public String server() { return prefs.getString("base",""); }
    public String conversation() { return prefs.getString("chat",""); }
    static String scope(Context c) { return c.getSharedPreferences(PREFS,0).getString("scope",""); }
    public void close() { closed = true; }

    public void pair(String base, String code, Callback callback) {
        execute(callback, () -> {
            URI uri = new URI(base.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null ||
                    uri.getQuery() != null || uri.getFragment() != null)
                throw new IOException("请输入完整的 HTTPS 服务地址");
            String endpoint = uri.toASCIIString().replaceAll("/+$","");
            if (code.trim().isEmpty()) throw new IOException("请输入配对码");
            JSONObject response = request(endpoint,"/v1/pair","POST",null,new JSONObject().put("code",code.trim()));
            String token = ChatStore.required(response,"access_token");
            String device = ChatStore.required(response,"device_id");
            String chat = ChatStore.required(response,"conversation_id");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,deviceKey());
            String sealed = encode(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)));
            boolean saved = prefs.edit().putString("credential",sealed).putString("iv",encode(cipher.getIV()))
                    .putString("base",endpoint).putString("chat",chat).putString("scope",endpoint+"\n"+device)
                    .remove("push_uploaded").remove("last_push_marker").commit();
            if (!saved) throw new IOException("无法保存设备配对信息，请重新配对");
            tryUploadRegistration();
        });
    }

    public void send(String chat,String body,Callback cb) {
        execute(cb, () -> {
            connected(chat);
            if (body == null || body.trim().isEmpty()) throw new IOException("消息为空");
            String owner = scope(context);
            String id = store.enqueue(owner,chat,body);
            deliver(owner,chat,id);
        });
    }
    public void retry(String chat,String clientId,Callback cb) {
        execute(cb, () -> { connected(chat); deliver(scope(context),chat,clientId); });
    }
    private void deliver(String owner,String chat,String id) throws Exception {
        String body = store.pendingBody(owner,chat,id);
        if (body == null) return;
        JSONObject response = request(server(),path(chat),"POST",token(),new JSONObject().put("client_message_id",id).put("text",body));
        store.accepted(owner,chat,id,ChatStore.required(response,"message_id"));
    }
    public void sync(String chat,Callback cb) {
        execute(cb, () -> {
            connected(chat);
            String owner = scope(context);
            String credential = token();
            long cursor = store.cursor(owner,chat);
            // Bound one foreground refresh. The next refresh continues from the committed cursor.
            for (int page=0; page<12; page++) {
                JSONObject json = request(server(),path(chat)+"?after_seq="+cursor,"GET",credential,null);
                cursor = store.applyPage(owner,chat,cursor,json);
                if (!json.getBoolean("has_more")) break;
            }
            tryUploadRegistration();
        });
    }
    private void connected(String chat) throws IOException {
        if (!paired()) throw new IOException("请先配对服务器");
        if (chat == null || chat.trim().isEmpty()) throw new IOException("缺少会话编号");
    }
    private static String path(String chat) { return "/v1/conversations/"+Uri.encode(chat)+"/messages"; }

    void uploadTool(String owner,String chat,String id,Callback cb) {
        execute(cb, () -> {
            connected(chat);
            if (!scope(context).equals(owner)) throw new IOException("此请求属于另一台已配对设备");
            String result = store.toolResult(owner,chat,id);
            if (result == null) return;
            putToolResult(chat,id,new JSONObject(result));
            store.transitionTool(owner,chat,id,"result_pending","complete",null);
        });
    }

    private void putToolResult(String chat, String id, JSONObject result) throws Exception {
        String route = "/v1/conversations/"+Uri.encode(chat)+"/tool-requests/"+Uri.encode(id)+"/result";
        request(server(),route,"PUT",token(),result);
    }

    void uploadRemoteTermuxResult(JSONObject entry, RemoteResultCallback callback) {
        NETWORK.execute(() -> {
            boolean retry = false;
            try {
                if (paired() && scope(context).equals(entry.getString("owner"))) {
                    putToolResult(entry.getString("chat"), entry.getString("request"), entry.getJSONObject("result"));
                }
            } catch (HttpFailure e) {
                retry = e.status == 408 || e.status == 429 || e.status >= 500;
            } catch (IOException e) {
                retry = true;
            } catch (Exception e) {
                // Invalid local state or a revoked device credential will not heal with an HTTP retry.
            }
            boolean shouldRetry = retry;
            MAIN.post(() -> callback.done(shouldRetry));
        });
    }

    public static void rememberPushRegistration(Context c,String fid) {
        if (fid == null || fid.trim().isEmpty()) return;
        AssistantApi api = new AssistantApi(c);
        NETWORK.execute(() -> {
            boolean changed = !fid.equals(api.prefs.getString("fid",""));
            SharedPreferences.Editor e = api.prefs.edit().putString("fid",fid).remove("push_uploaded");
            if (changed) e.putLong("registration_version",api.prefs.getLong("registration_version",0)+1);
            if (e.commit()) api.tryUploadRegistration();
        });
    }
    private void tryUploadRegistration() {
        String fid = prefs.getString("fid","");
        if (!paired() || fid.isEmpty()) return;
        boolean enabled = true;
        android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
        if (android.os.Build.VERSION.SDK_INT >= 24) enabled = manager.areNotificationsEnabled();
        if (enabled && android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel channel = manager.getNotificationChannel("assistant_replies");
            if (channel != null) enabled = channel.getImportance() != android.app.NotificationManager.IMPORTANCE_NONE;
        }
        String marker = fid+"/"+enabled;
        if (marker.equals(prefs.getString("push_uploaded",""))) return;
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("last_push_try",0) < 30000 && marker.equals(prefs.getString("last_push_marker",""))) return;
        long version = prefs.getLong("registration_version",0)+1;
        prefs.edit().putLong("last_push_try",now).putString("last_push_marker",marker).putLong("registration_version",version).apply();
        try {
            request(server(),"/v1/devices/me/push","PUT",token(),new JSONObject().put("fid",fid)
                    .put("notifications_enabled",enabled).put("binding_version",version));
            prefs.edit().putString("push_uploaded",marker).apply();
        } catch (Exception ignored) { /* Retried on foreground sync; chat delivery is independent. */ }
    }

    private void execute(Callback cb,Work work) {
        NETWORK.execute(() -> {
            String error = null;
            try { work.run(); }
            catch (JSONException e) { error = "服务器回复格式不符合助手接口"; }
            catch (java.net.URISyntaxException e) { error = "服务地址格式有误"; }
            catch (javax.net.ssl.SSLException e) { error = "安全连接失败，请检查服务器证书"; }
            catch (java.net.SocketTimeoutException e) { error = "服务器响应超时"; }
            catch (java.security.GeneralSecurityException e) { error = "设备凭证不可用，请重新配对"; }
            catch (IOException e) { error = e.getMessage() == null ? "无法连接服务器" : e.getMessage(); }
            catch (Exception e) { error = "操作未完成，请重试"; }
            String result = error;
            if (cb != null) MAIN.post(() -> { if (!closed) cb.done(result); });
        });
    }
    private static SecretKey deviceKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY)) return (SecretKey) ks.getKey(KEY,null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return kg.generateKey();
    }
    private static String encode(byte[] bytes) { return Base64.encodeToString(bytes,Base64.NO_WRAP); }
    private String token() throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, deviceKey(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.DEFAULT)));
        return new String(cipher.doFinal(Base64.decode(prefs.getString("credential",""),Base64.DEFAULT)),StandardCharsets.UTF_8);
    }
    private static JSONObject request(String base,String path,String method,String bearer,JSONObject body) throws Exception {
        HttpsURLConnection con = (HttpsURLConnection)new URL(base+path).openConnection();
        try {
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(8000); con.setReadTimeout(10000);
            con.setUseCaches(false); con.setRequestMethod(method);
            con.setRequestProperty("Accept","application/json");
            if (bearer != null) con.setRequestProperty("Authorization","Bearer "+bearer);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                con.setDoOutput(true); con.setRequestProperty("Content-Type","application/json; charset=utf-8");
                con.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = con.getOutputStream()) { out.write(bytes); }
            }
            int code = con.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpFailure(code);
            if (code == 204) return new JSONObject();
            try (InputStream in = con.getInputStream(); ByteArrayOutputStream response = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n=in.read(chunk)) != -1) {
                    if (response.size()+n > 4*1024*1024) throw new IOException("服务器返回的数据过大");
                    response.write(chunk,0,n);
                }
                return response.size()==0 ? new JSONObject() : new JSONObject(response.toString("UTF-8"));
            }
        } finally { con.disconnect(); }
    }
}
