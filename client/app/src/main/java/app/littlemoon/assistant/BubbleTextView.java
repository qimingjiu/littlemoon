package app.littlemoon.assistant;

import android.content.Context;
import android.text.Layout;
import android.widget.TextView;

/** A TextView that, once wrapped, narrows itself to its longest line so chat bubbles don't keep empty space on the right. */
public class BubbleTextView extends TextView {

    public BubbleTextView(Context context) {
        super(context);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        Layout layout = getLayout();
        if (layout == null || layout.getLineCount() < 2) return;
        float widest = 0f;
        for (int line = 0; line < layout.getLineCount(); line++) {
            widest = Math.max(widest, layout.getLineWidth(line));
        }
        int target = (int) Math.ceil(widest) + getCompoundPaddingLeft() + getCompoundPaddingRight();
        if (target < getMeasuredWidth()) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
        }
    }
}
