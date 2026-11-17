package in.shazverse.srklauncher;

import android.animation.ValueAnimator;
import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/** Holds one Home Screen widget (SRK built-in or a phone widget) with long-press, jiggle and drag. */
final class WidgetFrame extends FrameLayout {

    interface Host {
        void onWidgetLongPress(WidgetFrame f);

        void onWidgetRemove(WidgetFrame f);

        void onWidgetStartDrag(WidgetFrame f);
    }

    final String entry;
    final AppStore.WidgetSpec spec;
    int index;
    final View content;
    private final Host host;
    private final boolean system;
    private boolean editing, longFired, downOnMinus, dragStarted;
    private float downX, downY;
    private final int slop;
    private ValueAnimator jiggle;
    private final Paint minusBg = new Paint(Paint.ANTI_ALIAS_FLAG), minusFg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF minusRect = new RectF();
    private final RectF glassRect = new RectF();
    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            host.onWidgetLongPress(WidgetFrame.this);
        }
    };

    WidgetFrame(Context c, String entry, View content, Host host) {
        super(c);
        this.entry = entry;
        this.spec = AppStore.WidgetSpec.parse(entry);
        this.content = content;
        this.host = host;
        this.system = content instanceof AppWidgetHostView || (spec != null && "sys".equals(spec.kind));
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setWillNotDraw(false);
        setClipChildren(false);
        minusBg.setColor(0xF2D1D1D6);
        minusFg.setColor(0xFF1C1C1E);
        minusFg.setStrokeWidth(Ui.dp(2.2f));
        minusFg.setStrokeCap(Paint.Cap.ROUND);
        final float radius = Ui.dp(22);
        content.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        content.setClipToOutline(true);
        addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onDraw(Canvas c) {
        // Phone widgets get a glass card behind them (many are transparent).
        if (system) {
            glassRect.set(0, 0, getWidth(), getHeight());
            Glass.drawRound(c, this, glassRect, Ui.dp(22), 0x2EFFFFFF, 0x59000000);
        }
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (editing) {
            float r = Ui.dp(11);
            float cx = Ui.dp(6), cy = Ui.dp(6);
            minusRect.set(cx - r * 2, cy - r * 2, cx + r * 2, cy + r * 2);
            c.drawCircle(cx, cy, r, minusBg);
            c.drawLine(cx - r * 0.45f, cy, cx + r * 0.45f, cy, minusFg);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (content instanceof AppWidgetHostView && w > 0 && h > 0) {
            float d = getResources().getDisplayMetrics().density;
            try {
                ((AppWidgetHostView) content).updateAppWidgetSize(null,
                        (int) (w / d), (int) (h / d), (int) (w / d), (int) (h / d));
            } catch (Exception ignored) {
            }
        }
    }

    void setEditing(boolean on) {
        if (editing == on) return;
        editing = on;
        if (on) {
            int d = 140 + (int) (Math.random() * 40);
            jiggle = ValueAnimator.ofFloat(-0.9f, 0.9f);
            jiggle.setDuration(d);
            jiggle.setRepeatMode(ValueAnimator.REVERSE);
            jiggle.setRepeatCount(ValueAnimator.INFINITE);
            jiggle.addUpdateListener(a -> setRotation((float) a.getAnimatedValue()));
            jiggle.start();
        } else {
            if (jiggle != null) jiggle.cancel();
            jiggle = null;
            setRotation(0);
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (jiggle != null) jiggle.cancel();
        removeCallbacks(longPress);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (editing) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) startEditTouch(e);
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                longFired = false;
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > slop) removeCallbacks(longPress);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                break;
            default:
                break;
        }
        return longFired;
    }

    private void startEditTouch(MotionEvent e) {
        downX = e.getX();
        downY = e.getY();
        dragStarted = false;
        downOnMinus = minusRect.contains(downX, downY);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editing) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startEditTouch(e);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!downOnMinus && !dragStarted && Math.hypot(e.getX() - downX, e.getY() - downY) > slop) {
                        dragStarted = true;
                        host.onWidgetStartDrag(this);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (downOnMinus && minusRect.contains(e.getX(), e.getY())) host.onWidgetRemove(this);
                    return true;
                default:
                    return true;
            }
        }
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // Nothing inside handled the touch: still detect long-press on the frame.
            downX = e.getX();
            downY = e.getY();
            longFired = false;
            postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE
                && Math.hypot(e.getX() - downX, e.getY() - downY) > slop) {
            removeCallbacks(longPress);
            return false;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longPress);
        }
        return true;
    }
}
