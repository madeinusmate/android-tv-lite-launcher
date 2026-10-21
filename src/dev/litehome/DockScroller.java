package dev.litehome;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.HorizontalScrollView;

public final class DockScroller extends HorizontalScrollView {
    public DockScroller(Context context, AttributeSet attrs) {
        super(context, attrs);
        setSmoothScrollingEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        return super.requestChildRectangleOnScreen(child, rectangle, true);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        if (getChildCount() == 0) {
            return 0;
        }
        int scrollX = getScrollX();
        int viewLeft = scrollX + getPaddingLeft();
        int viewRight = scrollX + getWidth() - getPaddingRight();
        int delta = 0;
        if (rect.right > viewRight) {
            delta = rect.right - viewRight;
        } else if (rect.left < viewLeft) {
            delta = rect.left - viewLeft;
        }
        int viewport = getWidth() - getPaddingLeft() - getPaddingRight();
        int max = Math.max(0, getChildAt(0).getWidth() - viewport);
        int target = Math.max(0, Math.min(scrollX + delta, max));
        return target - scrollX;
    }
}
