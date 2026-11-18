package app.littlemoon.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final String MESSAGE_READY = "app.littlemoon.assistant.MESSAGE_READY";
    private static final int NOTIFICATION_PERMISSION = 6104;
    private static final int PICK_FIREBASE_CONFIG = 6135;
    private static final long SYNC_INTERVAL = 5000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> retries = new HashSet<>();
    private final Map<String,String> drafts = new HashMap<>();
    private final Runnable poll = this::sync;
    private AssistantApi api;
    private ChatStore store;
    private LocalTools tools;
    private ToolRequests requests;
    private ChatRows rows;
    private EditText input;
    private ListView messages;
    private String conversation = "";
    private String requestedMessage;
    private boolean resumed, syncing, sending;
    private String pendingNotificationConversation;
    private final BroadcastReceiver incomingMessage = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (MESSAGE_READY.equals(intent.getAction()) &&
                    conversation.equals(intent.getStringExtra("conversation_id"))) sync();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.assistant_chat);
        configureInsets();
        api = new AssistantApi(this);
        store = new ChatStore(this);
        tools = new LocalTools(this);
        requests = new ToolRequests(this,api);
        rows = new ChatRows(this, this::retry, entry -> requests.open(conversation,entry));
        messages = findViewById(R.id.messages);
        input = findViewById(R.id.input);
        // Android's automatic EditText state restoration has no notion of the paired account.
        input.setSaveEnabled(false);
        messages.setAdapter(rows);
        conversation = api.paired() ? api.conversation() : "";
        boolean sameScope = state != null && AssistantApi.scope(this).equals(state.getString("scope",""));
        if (sameScope) {
            conversation = state.getString("conversation", conversation);
            requestedMessage = state.getString("requestedMessage");
            pendingNotificationConversation = state.getString("pendingNotificationConversation");
            input.setText(state.getString("draft",""));
            input.setSelection(input.length());
        } else if (state == null) {
            readRoute(getIntent());
        }
        styleControls();
        findViewById(R.id.connection).setOnClickListener(v -> openConnection());
        findViewById(R.id.permissions).setOnClickListener(v -> startActivity(new Intent(this, PermissionsActivity.class)));
        findViewById(R.id.empty_action).setOnClickListener(v -> pairDialog());
        findViewById(R.id.tools).setOnClickListener(v -> chooseTool());
        findViewById(R.id.tool_close).setOnClickListener(v -> findViewById(R.id.tool_result).setVisibility(View.GONE));
        findViewById(R.id.tool_result_text).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("本机工具结果").setMessage(((TextView) v).getText()).setPositiveButton("完成", null).show());
        findViewById(R.id.send).setOnClickListener(v -> send());
        input.setOnEditorActionListener((v, id, event) -> {
            if (id != EditorInfo.IME_ACTION_SEND) return false;
            send();
            return true;
        });
        render(false);
        if (sameScope && state.containsKey("localResult")) showToolResult(state.getString("localResult"));
    }

    private void configureInsets() {
        View root = findViewById(R.id.page);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return windowInsets;
            });
            root.requestApplyInsets();
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private void styleControls() {
        input.setBackground(shape("#F1F4EF", 22));
        findViewById(R.id.empty_card).setBackground(shape("#EDF1E8",24));
        findViewById(R.id.tool_result).setBackground(shape("#E6EFE2",16));
        for (int id : new int[]{R.id.send, R.id.empty_action}) {
            findViewById(id).setBackground(new RippleDrawable(ColorStateList.valueOf(0x38FFFFFF), shape("#285542", 24), null));
        }
    }

    private void readRoute(Intent intent) {
        if (intent == null) return;
        String target = intent.getStringExtra("conversation_id");
        if (target == null || target.trim().isEmpty() || target.length() > 128) return;
        requestedMessage = intent.getStringExtra("message_id");
        if (api.paired()) {
            if (!target.equals(conversation)) {
                drafts.put(conversation,input.getText().toString());
                input.setText(drafts.get(target));
                input.setSelection(input.length());
                showError(null);
            }
            conversation = target;
        }
        else pendingNotificationConversation = target;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readRoute(intent);
        if (resumed) PushUiState.visibleConversation(api.paired() ? conversation : null);
        render(false);
        if (resumed) sync();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("scope",AssistantApi.scope(this));
        state.putString("draft",input.getText().toString());
        state.putString("conversation",conversation);
        state.putString("requestedMessage",requestedMessage);
        state.putString("pendingNotificationConversation",pendingNotificationConversation);
        if (findViewById(R.id.tool_result).getVisibility() == View.VISIBLE)
            state.putString("localResult", ((TextView)findViewById(R.id.tool_result_text)).getText().toString());
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(incomingMessage,new IntentFilter(MESSAGE_READY),Context.RECEIVER_NOT_EXPORTED);
        }
        PushUiState.visibleConversation(api.paired() ? conversation : null);
        tools.onResume();
        render(false);
        sync();
        RemoteTermux.schedule(this);
    }

    @Override protected void onPause() {
        resumed = false;
        if (Build.VERSION.SDK_INT >= 33) unregisterReceiver(incomingMessage);
        PushUiState.visibleConversation(null);
        main.removeCallbacks(poll);
        tools.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(poll);
        api.close();
        super.onDestroy();
    }

    private void sync() {
        main.removeCallbacks(poll);
        if (!resumed || syncing || !api.paired() || conversation.isEmpty()) return;
        final String target = conversation;
        syncing = true;
        api.sync(target, error -> {
            syncing = false;
            if (isDestroyed()) return;
            if (target.equals(conversation)) {
                showError(error);
                render(false);
            }
            if (resumed) main.postDelayed(poll, target.equals(conversation) ? SYNC_INTERVAL : 0);
        });
    }

    private void send() {
        if (!api.paired()) { pairDialog(); return; }
        String text = input.getText().toString().trim();
        if (text.isEmpty() || sending) return;
        sending = true;
        String original = input.getText().toString();
        String target = conversation;
        input.setText("");
        api.send(target, text, error -> {
            sending = false;
            if (isDestroyed() || !target.equals(conversation)) return;
            showError(error);
            render(true);
            if (error != null && input.length() == 0) {
                // Preflight or validation errors can occur before a local copy exists.
                boolean saved = false;
                for (ChatStore.Entry entry : store.messages(target)) {
                    if ("user".equals(entry.role) && text.equals(entry.text) && "pending".equals(entry.status)) { saved = true; break; }
                }
                if (!saved) input.setText(original);
            }
            if (error == null) sync();
        });
        render(true);
    }

    private void retry(String id) {
        if (!api.paired() || !retries.add(id)) return;
        final String target = conversation;
        api.retry(target, id, error -> {
            retries.remove(id);
            if (isDestroyed() || !target.equals(conversation)) return;
            showError(error);
            render(false);
            if (error == null) sync();
        });
    }

    private void render(boolean jumpToEnd) {
        boolean paired = api.paired();
        boolean wasNearBottom = rows.getCount() == 0 || messages.getLastVisiblePosition() >= rows.getCount()-2;
        int previousCount = rows.getCount();
        rows.replace(paired ? store.messages(conversation) : Collections.emptyList());
        boolean empty = rows.getCount() == 0;
        findViewById(R.id.empty).setVisibility(empty ? View.VISIBLE : View.GONE);
        messages.setVisibility(empty ? View.GONE : View.VISIBLE);
        ((TextView)findViewById(R.id.status)).setText(paired ? "已配对 · 私人会话" : "本机模式");
        ((TextView)findViewById(R.id.empty_title)).setText(paired ? "给助手发条消息" : "连接你的助手");
        ((TextView)findViewById(R.id.empty_detail)).setText(paired ? "在下方输入第一条消息。" : "配对后即可聊天，也可以先用输入框旁的本机工具。");
        findViewById(R.id.empty_action).setVisibility(paired ? View.GONE : View.VISIBLE);
        if (requestedMessage != null) {
            int index = rows.find(requestedMessage);
            if (index >= 0) {
                messages.setSelection(index);
                requestedMessage = null;
                return;
            }
        }
        if (!empty && (jumpToEnd || (wasNearBottom && rows.getCount() > previousCount))) {
            messages.setSelection(rows.getCount()-1);
        }
    }

    private void showError(String error) {
        TextView banner = findViewById(R.id.network_error);
        banner.setVisibility(error == null ? View.GONE : View.VISIBLE);
        if (error != null) banner.setText(error);
    }

    private void openConnection() {
        boolean configured = PushSetup.configured(this);
        String detail = (api.paired() ? api.server() : "尚未配对服务器") + "\n\n" + PushSetup.description(this);
        new AlertDialog.Builder(this).setTitle("服务器与通知").setMessage(detail)
                .setNegativeButton("完成",null)
                .setNeutralButton(api.paired() ? "重新配对" : "配对服务器",(d,w) -> pairDialog())
                .setPositiveButton(configured ? "回复提醒" : "导入推送配置",(d,w) -> {
                    if (configured) { PushSetup.register(this); notificationSettings(); return; }
                    try {
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_FIREBASE_CONFIG);
                    } catch (android.content.ActivityNotFoundException e) { showToolResult("设备没有可用的文件选择器。"); }
                }).show();
    }

    private void pairDialog() {
        String previousScope = AssistantApi.scope(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24),dp(8),dp(24),0);
        EditText url = field(form,"服务器地址","https://", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (api.paired()) url.setText(api.server());
        EditText code = field(form,"配对码","输入服务器提供的配对码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        url.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        code.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        TextView errorText = text("",13,"#9C3728");
        errorText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(errorText);
        if (!PushSetup.configured(this)) form.addView(text("可先配对聊天。需要锁屏提醒时，在“服务器与通知”里导入推送配置。",13,"#55665E"));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("连接服务器").setView(form)
                .setNegativeButton("取消",null).setPositiveButton("配对",null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String host = url.getText().toString().trim();
            String token = code.getText().toString().trim();
            if (host.isEmpty() || token.isEmpty()) {errorText.setText("请填写 HTTPS 地址和配对码"); return;}
            Button pair = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            pair.setEnabled(false);
            dialog.setCancelable(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            errorText.setText("正在配对…");
            api.pair(host,token,error -> {
                if (isDestroyed()) return;
                if (error != null) {
                    dialog.setCancelable(true);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                    pair.setEnabled(true);
                    errorText.setText(error);
                    return;
                }
                conversation = pendingNotificationConversation == null ? api.conversation() : pendingNotificationConversation;
                pendingNotificationConversation = null;
                if (!previousScope.isEmpty() && !previousScope.equals(AssistantApi.scope(this))) {
                    input.setText("");
                    drafts.clear();
                    retries.clear();
                    requestedMessage = null;
                    findViewById(R.id.tool_result).setVisibility(View.GONE);
                }
                if (resumed) PushUiState.visibleConversation(conversation);
                dialog.dismiss();
                showError(null);
                render(true);
                sync();
                if (resumed && PushSetup.configured(this) && Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    new AlertDialog.Builder(this).setTitle("接收回复提醒？").setMessage("锁屏后也可以看到新回复提醒。")
                            .setNegativeButton("稍后",null).setPositiveButton("开启",(d,w) -> notificationSettings()).show();
                }
            });
        }));
        dialog.show();
    }

    private void notificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                (!getPreferences(MODE_PRIVATE).getBoolean("askedForNotifications",false) ||
                        shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedForNotifications",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);
            return;
        }
        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));
    }

    private EditText field(LinearLayout form, String label, String hint, int type) {
        TextView title = text(label,14,"#384A41");
        form.addView(title);
        EditText edit = new EditText(this);
        edit.setId(View.generateViewId());
        title.setLabelFor(edit.getId());
        edit.setInputType(type);
        edit.setSingleLine(true);
        edit.setHint(hint);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin = dp(10);
        form.addView(edit,p);
        return edit;
    }

    private void chooseTool() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(14),dp(4),dp(14),dp(4));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("本机工具").setView(menu)
                .setNegativeButton("关闭",null).create();
        tool(menu,R.drawable.tool_music,"播放音乐","选择手机里的一首歌",() -> { dialog.dismiss(); tools.playMusic(); });
        tool(menu,R.drawable.ic_location,"获取一次位置","结果只显示在本机",() -> { dialog.dismiss(); tools.locateOnce(); });
        tool(menu,R.drawable.tool_term,"检查 Termux","读取已安装的 Python 版本",() -> { dialog.dismiss(); tools.checkPython(); });
        dialog.show();
    }

    private void tool(LinearLayout menu,int icon,String title,String description,Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(68));
        row.setPadding(dp(10),dp(6),dp(10),dp(6));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1C244632),shape("#FFFFFF",12),null));
        ImageView mark = new ImageView(this);
        mark.setImageResource(icon);
        mark.setColorFilter(Color.parseColor("#315442"));
        mark.setBackground(shape("#EAF1E8",13));
        int p=dp(9);
        mark.setPadding(p,p,p,p);
        row.addView(mark,new LinearLayout.LayoutParams(dp(40),dp(40)));
        LinearLayout caption=new LinearLayout(this);
        caption.setOrientation(LinearLayout.VERTICAL);
        TextView label=text(title,16,"#223A2C");
        label.setTypeface(null,Typeface.BOLD);
        caption.addView(label);
        caption.addView(text(description,13,"#5A6A60"));
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(0,-2,1);
        body.leftMargin=dp(12);
        row.addView(caption,body);
        row.setOnClickListener(v -> action.run());
        row.setFocusable(true);
        menu.addView(row);
    }

    public void showToolResult(String result) {
        if (TextUtils.isEmpty(result) || isDestroyed()) return;
        ((TextView)findViewById(R.id.tool_result_text)).setText(result);
        findViewById(R.id.tool_result).setVisibility(View.VISIBLE);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if (requestCode != NOTIFICATION_PERMISSION) tools.onPermissionResult(requestCode);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode == PICK_FIREBASE_CONFIG) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                PushSetup.importConfig(this,data.getData(),error -> {
                    if (isDestroyed()) return;
                    if (error != null) { showToolResult(error); return; }
                    showToolResult("已导入推送项目，正在向 Google 注册这台设备。");
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) notificationSettings();
                });
            }
            return;
        }
        tools.onActivityResult(requestCode,resultCode,data);
        requests.onActivityResult(requestCode,resultCode,data);
    }

    private TextView text(String content, int size, String color) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(Color.parseColor(color));
        return view;
    }

    private GradientDrawable shape(String color,int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor(color));
        background.setCornerRadius(dp(radius));
        return background;
    }

    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
