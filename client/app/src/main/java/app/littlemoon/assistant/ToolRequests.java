package app.littlemoon.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Requests are read from the authenticated timeline. An FCM payload cannot dispatch a tool. */
final class ToolRequests {
    private static final int PICK_MUSIC = 7134;
    private final Activity activity;
    private final AssistantApi api;
    private final ChatStore store;
    private final SharedPreferences picker;

    ToolRequests(Activity activity, AssistantApi api) {
        this.activity = activity;
        this.api = api;
        store = new ChatStore(activity);
        picker = activity.getSharedPreferences("requested_audio_picker", 0);
    }

    void open(String chat,ChatStore.Entry entry) {
        String owner = AssistantApi.scope(activity);
        if (owner.isEmpty() || entry.tool == null) return;
        if ("result_pending".equals(entry.status)) { upload(owner,chat,entry.id); return; }
        if ("complete".equals(entry.status)) { toast("结果已送回助手"); return; }
        if ("started".equals(entry.status)) {
            new AlertDialog.Builder(activity).setTitle("这项操作已经开始")
                    .setMessage("若上次操作中断，可以将状态告知助手。")
                    .setPositiveButton("报告结果未知",(d,w) -> finish(owner,chat,entry.id,"unknown",null))
                    .setNegativeButton("返回",null).show();
            return;
        }
        String tool = entry.tool.optString("name");
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity);
        String title = entry.text.trim();
        dialog.setTitle(title.isEmpty() ? "助手请求" : title.substring(0,Math.min(160,title.length())));
        if ("choose_option".equals(tool)) {
            JSONArray options = entry.tool.optJSONArray("options");
            if (options == null || options.length() < 1 || options.length() > 6) { toast("可选项格式有误"); return; }
            String[] labels = new String[options.length()];
            for (int i=0;i<labels.length;i++) {
                Object value = options.opt(i);
                if (!(value instanceof String) || ((String)value).trim().isEmpty() || ((String)value).length()>120) {
                    toast("可选项格式有误"); return;
                }
                labels[i] = (String)value;
            }
            dialog.setItems(labels,(d,i) -> {
                if (claim(owner,chat,entry.id)) finish(owner,chat,entry.id,"selected",labels[i]);
            });
        } else if ("play_music".equals(tool)) {
            dialog.setMessage("从这台手机选一首歌，交给系统播放器。只向助手返回打开状态。")
                    .setPositiveButton("选择音频",(d,w) -> pick(owner,chat,entry.id));
        } else { toast("此版本尚不支持这项工具"); return; }
        dialog.setNegativeButton("稍后",null).setNeutralButton("拒绝",(d,w) -> {
            if (claim(owner,chat,entry.id)) finish(owner,chat,entry.id,"declined",null);
        }).show();
    }

    private boolean claim(String owner,String chat,String id) {
        boolean ok = store.transitionTool(owner,chat,id,"ready","started",null);
        if (!ok) toast("请求状态已更新");
        return ok;
    }
    private void pick(String owner,String chat,String id) {
        if (!claim(owner,chat,id)) return;
        if (!picker.edit().putString("owner",owner).putString("chat",chat).putString("id",id).commit()) {
            finish(owner,chat,id,"failed",null); return;
        }
        try {
            activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("audio/*"),PICK_MUSIC);
        } catch (ActivityNotFoundException e) {
            picker.edit().clear().apply(); finish(owner,chat,id,"unavailable",null);
        }
    }

    void onActivityResult(int code,int result,Intent data) {
        if (code != PICK_MUSIC || !picker.contains("id")) return;
        String owner = picker.getString("owner",""), chat = picker.getString("chat",""), id = picker.getString("id","");
        picker.edit().clear().apply();
        if (!owner.equals(AssistantApi.scope(activity))) return;
        Uri file = data == null ? null : data.getData();
        if (result != Activity.RESULT_OK || file == null || !"content".equals(file.getScheme())) {
            finish(owner,chat,id,"cancelled",null); return;
        }
        try {
            String mime = activity.getContentResolver().getType(file);
            activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(file,
                    mime != null && mime.startsWith("audio/") ? mime : "audio/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            finish(owner,chat,id,"opened_player",null);
        } catch (ActivityNotFoundException | SecurityException e) { finish(owner,chat,id,"unavailable",null); }
    }

    private void finish(String owner,String chat,String id,String status,String choice) {
        JSONObject result = new JSONObject();
        try { result.put("status",status); if (choice != null) result.put("choice",choice); }
        catch (JSONException e) { throw new IllegalStateException(e); }
        if (store.transitionTool(owner,chat,id,"started","result_pending",result.toString())) upload(owner,chat,id);
    }
    private void upload(String owner,String chat,String id) {
        api.uploadTool(owner,chat,id,error -> toast(error == null ? "结果已送回助手" : "结果已保存在手机上，点请求可重新发送"));
    }
    private void toast(String message) { Toast.makeText(activity,message,Toast.LENGTH_SHORT).show(); }
}
