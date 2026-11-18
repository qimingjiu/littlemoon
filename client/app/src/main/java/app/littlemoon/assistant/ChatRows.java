package app.littlemoon.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

final class ChatRows extends BaseAdapter {
    interface RetryListener { void retry(String id); }
    interface ToolListener { void open(ChatStore.Entry entry); }

    private final Context context;
    private final RetryListener retryListener;
    private final ToolListener toolListener;
    private final List<ChatStore.Entry> rows = new ArrayList<>();

    ChatRows(Context context, RetryListener listener, ToolListener toolListener) {
        this.context = context;
        this.retryListener = listener;
        this.toolListener = toolListener;
    }

    void replace(List<ChatStore.Entry> updated) {
        if (rows.size() == updated.size()) {
            boolean identical = true;
            for (int i = 0; i < rows.size(); i++) {
                ChatStore.Entry old = rows.get(i);
                ChatStore.Entry next = updated.get(i);
                if (!old.id.equals(next.id) || !old.status.equals(next.status) ||
                        !old.role.equals(next.role) || !old.text.equals(next.text)) {
                    identical = false;
                    break;
                }
            }
            if (identical) return;
        }
        rows.clear();
        rows.addAll(updated);
        notifyDataSetChanged();
    }

    int find(String id) {
        for (int i = 0; i < rows.size(); i++) {
            if (id.equals(rows.get(i).id)) return i;
        }
        return -1;
    }

    @Override public int getCount() { return rows.size(); }
    @Override public ChatStore.Entry getItem(int index) { return rows.get(index); }
    @Override public long getItemId(int index) { return index; }
    @Override public boolean isEnabled(int position) { return true; }
    @Override public boolean areAllItemsEnabled() { return false; }

    @Override public View getView(int index, View recycled, ViewGroup parent) {
        Bubble bubble = recycled == null ? new Bubble() : (Bubble) recycled.getTag();
        ChatStore.Entry item = getItem(index);
        boolean mine = "user".equals(item.role);
        boolean pending = mine && "pending".equals(item.status);
        boolean tool = "tool_request".equals(item.role) && item.tool != null;
        bubble.row.setGravity(mine ? Gravity.END : Gravity.START);
        bubble.row.setPadding(dp(mine ? 46 : 14), dp(3), dp(mine ? 14 : 46), dp(3));
        bubble.container.setBackground(mine ? bubble.outgoing : bubble.incoming);
        bubble.message.setText(item.text);
        bubble.state.setVisibility(mine ? View.VISIBLE : View.GONE);
        bubble.state.setText(pending ? "待发送 · 点此重试" : "sending".equals(item.status) ? "发送中…" : "已发送");
        bubble.state.setTextColor(Color.parseColor(pending ? "#774420" : "#556C60"));
        bubble.state.setOnClickListener(pending ? v -> retryListener.retry(item.id) : null);
        bubble.state.setClickable(pending);
        bubble.state.setFocusable(pending);
        bubble.state.setMinHeight(pending ? dp(44) : 0);
        bubble.toolAction.setVisibility(tool ? View.VISIBLE : View.GONE);
        if (tool) {
            String label;
            boolean offerAction = true;
            switch (item.status) {
                case "ready":
                    label = "play_music".equals(item.tool.optString("name")) ? "选择音乐…" : "查看选项…";
                    break;
                case "started": label = "查看处理状态"; break;
                case "result_pending": label = "重试提交选择"; break;
                default: label = "已处理"; offerAction = false;
            }
            bubble.toolAction.setText(label);
            bubble.toolAction.setEnabled(offerAction);
            bubble.toolAction.setOnClickListener(offerAction ? v -> toolListener.open(item) : null);
        }
        return bubble.row;
    }

    private int dp(int amount) {
        return Math.round(context.getResources().getDisplayMetrics().density * amount);
    }

    private GradientDrawable shape(String fill, boolean mine) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(Color.parseColor(fill));
        float r = dp(17), point = dp(4);
        result.setCornerRadii(new float[]{r,r,r,r,mine ? point : r,mine ? point : r,mine ? r : point,mine ? r : point});
        if (!mine) result.setStroke(dp(1), Color.parseColor("#E0E5DF"));
        return result;
    }

    private final class Bubble {
        final LinearLayout row = new LinearLayout(context);
        final LinearLayout container = new LinearLayout(context);
        final BubbleTextView message = new BubbleTextView(context);
        final TextView state = new TextView(context);
        final android.widget.Button toolAction = new android.widget.Button(context);
        final GradientDrawable outgoing = shape("#D9EBDD", true);
        final GradientDrawable incoming = shape("#FFFFFF", false);

        Bubble() {
            row.setTag(this);
            row.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, -2));
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(dp(13), dp(9), dp(13), dp(9));
            row.addView(container, new LinearLayout.LayoutParams(-2,-2));
            message.setTextSize(16);
            message.setTextColor(Color.parseColor("#1D3025"));
            message.setTextIsSelectable(true);
            message.setLineSpacing(dp(1), 1);
            container.addView(message, new LinearLayout.LayoutParams(-2,-2));
            state.setTextSize(11);
            state.setPadding(0,dp(2),0,0);
            state.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            container.addView(state, new LinearLayout.LayoutParams(-1,-2));
            toolAction.setTextSize(14);
            toolAction.setTextColor(Color.parseColor("#285542"));
            container.addView(toolAction,new LinearLayout.LayoutParams(-2,-2));
        }
    }
}
