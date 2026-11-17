package in.shazverse.srklauncher;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

/** Horizontal home-screen pager with swipe-down and long-press detection. */
public class PagerView extends ViewGroup {

    interface Listener {
        void onPageChanged(int page);

        void onScrollPosition(float pagePos);

        boolean canSwipeDown(int page);

        void onSwipeDown(float x, float y);

        void onEmptyLongPress();
    }

    private static final int NONE = 0, HORIZONTAL = 1, VERTICAL_DOWN = 2, VERTICAL_OTHER = 3;

    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final int slop, minFling;
    private VelocityTracker vt;
    private float downX, downY, lastX;
    private int mode = NONE;
    private int current;
    private Listener listener;

    public PagerView(Context c) {
        super(c);
        scroller = new OverScroller(c, new DecelerateInterpolator(2.2f));
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 6;
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (mode == NONE && listener != null) listener.onEmptyLongPress();
            }
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    int getCurrent() {
        return current;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        int cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(cw, ch);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(i * w, 0, (i + 1) * w, h);
        if (scroller.isFinished() && mode != HORIZONTAL) scrollTo(current * w, 0);
    }

    void setPage(int p, boolean animate) {
        int n = getChildCount();
        if (n == 0) {
            current = 0;
            return;
        }
        p = Math.max(0, Math.min(p, n - 1));
        int w = getWidth();
        boolean changed = p != current;
        current = p;
        if (w == 0 || !animate) {
            scroller.forceFinished(true);
            scrollTo(p * w, 0);
        } else {
            int dx = p * w - getScrollX();
            int dur = Math.min(420, 220 + (int) (Math.abs(dx) * 0.25f / getResources().getDisplayMetrics().density));
            scroller.startScroll(getScrollX(), 0, dx, 0, dur);
            postInvalidateOnAnimation();
        }
        if (changed && listener != null) listener.onPageChanged(p);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (listener != null && getWidth() > 0) listener.onScrollPosition(l / (float) getWidth());
    }

    private void detect(MotionEvent e) {
        float dx = e.getX() - downX, dy = e.getY() - downY;
        float adx = Math.abs(dx), ady = Math.abs(dy);
        if (adx > slop && adx > ady) {
            mode = HORIZONTAL;
            lastX = e.getX();
            ViewGroupParentHelper.disallow(this);
        } else if (dy > slop * 2 && dy > adx * 1.5f && listener != null && listener.canSwipeDown(current)) {
            mode = VERTICAL_DOWN;
            listener.onSwipeDown(downX, downY);
        } else if (ady > slop && ady > adx) {
            mode = VERTICAL_OTHER;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = e.getY();
                mode = NONE;
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                    mode = HORIZONTAL;
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (mode == NONE) detect(e);
                return mode == HORIZONTAL || mode == VERTICAL_DOWN;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mode == HORIZONTAL) settle(0);
                mode = NONE;
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        gestures.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = e.getY();
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                    mode = HORIZONTAL;
                } else {
                    mode = NONE;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode == NONE) detect(e);
                if (mode == HORIZONTAL) {
                    float d = lastX - e.getX();
                    lastX = e.getX();
                    int max = Math.max(0, (getChildCount() - 1) * getWidth());
                    float nx = getScrollX() + d;
                    if (nx < 0 || nx > max) d *= 0.35f;
                    scrollBy(Math.round(d), 0);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mode == HORIZONTAL) {
                    vt.computeCurrentVelocity(1000);
                    settle(vt.getXVelocity());
                }
                recycle();
                mode = NONE;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (mode == HORIZONTAL) settle(0);
                recycle();
                mode = NONE;
                return true;
            default:
                return true;
        }
    }

    private void settle(float velocity) {
        int w = getWidth();
        if (w == 0) return;
        float pos = getScrollX() / (float) w;
        int target;
        if (velocity < -minFling) target = (int) Math.floor(pos) + 1;
        else if (velocity > minFling) target = (int) Math.ceil(pos) - 1;
        else target = Math.round(pos);
        current = -1; // always snap; page listener is idempotent
        setPage(target, true);
    }

    private void recycle() {
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
    }

    /** Tiny helper so the pager can stop ancestors from stealing a horizontal drag. */
    static final class ViewGroupParentHelper {
        static void disallow(View v) {
            if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
        }
    }
}
