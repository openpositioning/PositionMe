package com.openpositioning.PositionMe.presentation.fragment;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

/**
 * LinearLayout that intercepts downward swipe gestures across its full area,
 * while still allowing child views (buttons, switches, etc.) to receive tap events normally.
 *
 * Uses onInterceptTouchEvent so that:
 * - Tapping a button inside the layout works as usual.
 * - Swiping downward anywhere on the layout triggers the drag callbacks.
 */
public class SwipeDownLinearLayout extends LinearLayout {

    public interface OnSwipeDownListener {
        /** Called each frame while the user is dragging down; dy >= 0. */
        void onDrag(float dy);
        /** Called when the finger lifts; decide whether to hide or spring back. */
        void onRelease(float dy);
    }

    private float startY;
    private boolean intercepting = false;
    private final int touchSlop;
    private OnSwipeDownListener swipeListener;

    public SwipeDownLinearLayout(Context context) {
        this(context, null);
    }

    public SwipeDownLinearLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SwipeDownLinearLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setOnSwipeDownListener(OnSwipeDownListener l) {
        swipeListener = l;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                startY = ev.getRawY();
                intercepting = false;
                break;

            case MotionEvent.ACTION_MOVE:
                float dy = ev.getRawY() - startY;
                // Only start intercepting for a clear downward swipe
                if (dy > touchSlop * 1.5f && !intercepting) {
                    intercepting = true;
                    return true;   // steal the event stream from child views
                }
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                intercepting = false;
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!intercepting) return super.onTouchEvent(ev);

        switch (ev.getAction()) {
            case MotionEvent.ACTION_MOVE:
                float dy = ev.getRawY() - startY;
                if (dy >= 0 && swipeListener != null) {
                    swipeListener.onDrag(dy);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (swipeListener != null) {
                    swipeListener.onRelease(Math.max(0, ev.getRawY() - startY));
                }
                intercepting = false;
                return true;
        }
        return super.onTouchEvent(ev);
    }
}
