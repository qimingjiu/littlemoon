package app.littlemoon.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/** Public Android client identifiers only. Sender credentials are never accepted or stored. */
final class PushSetup {
    interface Callback { void done(String error); }
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor();
    private static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("firebase_android_project",Context.MODE_PRIVATE);
    }
    static boolean configured(Context ctx) {
        try { FirebaseApp.getInstance(); return true; }
        catch (IllegalStateException absent) { return false; }
    }
    static void restore(Context ctx) {
        if (configured(ctx)) return;
        SharedPreferences p = settings(ctx);
        if (!p.contains("project")) return;
        initialize(ctx,p.getString("project",""),p.getString("app",""),p.getString("sender",""),p.getString("api",""));
    }
    private static void initialize(Context ctx, String project,String app,String sender,String key) {
        FirebaseOptions options = new FirebaseOptions.Builder().setProjectId(project).setApplicationId(app)
                .setGcmSenderId(sender).setApiKey(key).build();
        FirebaseApp.initializeApp(ctx,options);
    }
    static void register(Context ctx) {
        if (!configured(ctx)) return;
        FirebaseMessaging.getInstance().register().addOnCompleteListener(task ->
                settings(ctx).edit().putBoolean("last_registration_failed",!task.isSuccessful()).apply());
    }
    static String description(Context ctx) {
        if (!configured(ctx)) return "导入自己 Firebase 项目的 Android 配置后，可以接收回复提醒。";
        SharedPreferences p = ctx.getSharedPreferences("assistant.server",0);
        String project = FirebaseApp.getInstance().getOptions().getProjectId();
        String state = p.contains("fid") ? (p.contains("push_uploaded") ? "设备注册信息已传给服务器" : "设备已向 Google 注册，等待绑定服务器") :
                (settings(ctx).getBoolean("last_registration_failed",false) ? "设备注册失败，请检查网络、Google Play 服务和项目配置" : "等待设备向 Google 注册");
        return "Firebase 项目："+project+"\n"+state;
    }
    static void importConfig(Context context, Uri file, Callback callback) {
        Context appContext = context.getApplicationContext();
        FILES.execute(() -> {
            String error = null;
            try {
                JSONObject json = read(appContext,file);
                if (json.has("private_key") || "service_account".equals(json.optString("type")))
                    throw new IOException("请选 Android 客户端配置，服务账号私钥不能放在手机上。");
                JSONObject info = json.getJSONObject("project_info");
                String project = ChatStore.required(info,"project_id"), sender = ChatStore.required(info,"project_number");
                JSONArray clients = json.getJSONArray("client");
                JSONObject match = null;
                for (int i=0;i<clients.length();i++) {
                    JSONObject candidate = clients.getJSONObject(i);
                    JSONObject android = candidate.getJSONObject("client_info").optJSONObject("android_client_info");
                    if (android != null && appContext.getPackageName().equals(android.optString("package_name"))) { match=candidate; break; }
                }
                if (match == null) throw new IOException("该文件没有为 "+appContext.getPackageName()+" 注册的 Android 应用。");
                String app = ChatStore.required(match.getJSONObject("client_info"),"mobilesdk_app_id");
                String key = ChatStore.required(match.getJSONArray("api_key").getJSONObject(0),"current_key");
                if (!app.startsWith("1:"+sender+":android:") || !sender.matches("[0-9]+"))
                    throw new IOException("项目编号与 Android 应用标识不匹配。");
                if (configured(appContext)) throw new IOException("这台设备已经配置了推送项目。");
                if (!settings(appContext).edit().putString("project",project).putString("app",app)
                        .putString("sender",sender).putString("api",key).commit()) throw new IOException("无法保存推送配置。");
                initialize(appContext,project,app,sender,key);
                register(appContext);
            } catch (IOException e) { error=e.getMessage(); }
            catch (Exception e) { error="无法读取 Android 配置，请从 Firebase 控制台重新下载 google-services.json。"; }
            String result=error;
            new Handler(Looper.getMainLooper()).post(() -> callback.done(result));
        });
    }
    private static JSONObject read(Context ctx,Uri uri) throws IOException,JSONException {
        try (InputStream in=ctx.getContentResolver().openInputStream(uri); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            if (in==null) throw new IOException("无法打开所选文件。");
            byte[] buf=new byte[4096]; int count;
            while ((count=in.read(buf))!=-1) {
                if (out.size()+count>512*1024) throw new IOException("配置文件太大，请选择 Android 客户端配置。");
                out.write(buf,0,count);
            }
            return new JSONObject(out.toString("UTF-8"));
        }
    }
}
