package in.shazverse.srklauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;

import java.util.List;

/** An app icon with label, notification badge and iOS-style "jiggle" edit mode. */
public class AppIconView extends View {

    interface Callbacks {
        void onIconClick(AppIconView v);

        boolean onIconLongClick(AppIconView v);

        void onIconRemove(AppIconView v);

        void onIconStartDrag(AppIconView v);
    }

    static final String HOME = "home", DOCK = "dock", OTHER = "other", IN_FOLDER = "folder";

    /** The app, or null when this icon is a Home Screen folder. */
    final AppInfo app;
    final String folderId;
    final List<AppInfo> folderApps;
    final String label;
    /** For icons shown inside an open folder: the folder they belong to. */
    String ownerFolder;
    final int iconSize;
    final boolean showLabel;
    final Callbacks cb;
    String place = OTHER;
    int index = -1;

    private boolean editing;
    private int badge;
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint;
    private final Paint badgeBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgeText;
    private final Paint minusBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minusFg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF iconRect = new RectF(), minusRect = new RectF(), tmp = new RectF();
    private ValueAnimator jiggle;
    private float downX, downY;
    private boolean downOnMinus, dragStarted;
    private final int slop;
    private String shortLabel;
    private int shortLabelW = -1;

    AppIconView(Context c, AppInfo app, int iconSize, boolean showLabel, Callbacks cb) {
        this(c, app, null, null, app.label, iconSize, showLabel, cb);
    }

    /** A Home Screen folder icon. */
    AppIconView(Context c, String folderId, String name, List<AppInfo> apps, int iconSize, Callbacks cb) {
        this(c, null, folderId, apps, name, iconSize, true, cb);
    }

    private AppIconView(Context c, AppInfo app, String folderId, List<AppInfo> folderApps, String label,
                        int iconSize, boolean showLabel, Callbacks cb) {
        super(c);
        this.app = app;
        this.folderId = folderId;
        this.folderApps = folderApps;
        this.label = label;
        this.iconSize = iconSize;
        this.showLabel = showLabel;
        this.cb = cb;
        labelPaint = Ui.paint(Ui.WHITE, 11.5f, Ui.REGULAR);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setShadowLayer(Ui.dp(2), 0, Ui.dp(0.5f), 0x80000000);
        badgeBg.setColor(Ui.RED);
        badgeText = Ui.paint(Ui.WHITE, 12, Ui.MEDIUM);
        badgeText.setTextAlign(Paint.Align.CENTER);
        minusBg.setColor(0xF2D1D1D6);
        minusFg.setColor(0xFF1C1C1E);
        minusFg.setStrokeWidth(Ui.dp(2.2f));
        minusFg.setStrokeCap(Paint.Cap.ROUND);
        dim.setColor(0x59000000);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setClickable(true);
        setLongClickable(true);
        setHapticFeedbackEnabled(true);
        setContentDescription(label);
        setOnClickListener(v -> {
            if (!editing) cb.onIconClick(this);
        });
        setOnLongClickListener(v -> !editing && cb.onIconLongClick(this));
    }

    boolean isFolder() {
        return folderId != null;
    }

    /** The Home Screen entry string this icon represents. */
    String entry() {
        return folderId != null ? "folder:" + folderId : app.key;
    }

    boolean removable() {
        return HOME.equals(place) || DOCK.equals(place);
    }

    void setBadge(int n) {
        if (n != badge) {
            badge = n;
            invalidate();
        }
    }

    void setEditing(boolean on) {
        if (on && !removable()) on = false;
        if (editing == on) return;
        editing = on;
        if (on) {
            int d = 120 + (int) (Math.random() * 40);
            jiggle = ValueAnimator.ofFloat(-1.8f, 1.8f);
            jiggle.setDuration(d);
            jiggle.setRepeatMode(ValueAnimator.REVERSE);
            jiggle.setRepeatCount(ValueAnimator.INFINITE);
            jiggle.addUpdateListener(a -> setRotation((float) a.getAnimatedValue()));
            jiggle.start();
            jiggle.setCurrentPlayTime((long) (Math.random() * d));
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
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = resolveSize(iconSize + Ui.dp(20), ws);
        int h = resolveSize(iconSize + Ui.dp(8) + (showLabel ? Ui.dp(22) : 0), hs);
        setMeasuredDimension(w, h);
    }

    private boolean wasPressed;

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        boolean p = isPressed() && !editing;
        if (p != wasPressed) {
            wasPressed = p;
            animate().scaleX(p ? 0.9f : 1f).scaleY(p ? 0.9f : 1f)
                    .setInterpolator(p ? Motion.EASE_OUT : Motion.SPRING).setDuration(p ? 120 : 380).start();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float contentH = iconSize + (showLabel ? Ui.dp(6) + labelPaint.getTextSize() : 0);
        float top = Math.max(Ui.dp(2), (h - contentH) / 2f);
        float left = (w - iconSize) / 2f;
        iconRect.set(left, top, left + iconSize, top + iconSize);
        float r = iconSize * 0.225f;
        if (folderId != null) {
            Glass.drawRound(c, this, iconRect, r, 0x40FFFFFF, 0x66FFFFFF);
            float pad = iconSize * 0.12f, g = iconSize * 0.06f;
            float m = (iconSize - pad * 2 - g * 2) / 3f;
            for (int i = 0; i < 9 && folderApps != null && i < folderApps.size(); i++) {
                AppInfo a = folderApps.get(i);
                if (a.icon == null) continue;
                float l = iconRect.left + pad + (i % 3) * (m + g), t = iconRect.top + pad + (i / 3) * (m + g);
                tmp.set(l, t, l + m, t + m);
                c.drawBitmap(a.icon, null, tmp, bmp);
            }
        } else if (app.icon != null) {
            c.drawBitmap(app.icon, null, iconRect, bmp);
        }
        if (isPressed() && !editing) c.drawRoundRect(iconRect, r, r, dim);

        if (showLabel) {
            int avail = w - Ui.dp(2);
            if (avail != shortLabelW) {
                shortLabel = TextUtils.ellipsize(this.label, labelPaint, avail, TextUtils.TruncateAt.END).toString();
                shortLabelW = avail;
            }
            c.drawText(shortLabel, w / 2f, iconRect.bottom + Ui.dp(5) - labelPaint.ascent(), labelPaint);
        }

        if (badge > 0 && !editing) {
            String s = badge > 99 ? "99+" : String.valueOf(badge);
            float br = Ui.dp(10.5f);
            float bw = Math.max(br * 2, badgeText.measureText(s) + Ui.dp(10));
            float cx = iconRect.right - Ui.dp(3), cy = iconRect.top + Ui.dp(3);
            tmp.set(cx - bw / 2, cy - br, cx + bw / 2, cy + br);
            c.drawRoundRect(tmp, br, br, badgeBg);
            c.drawText(s, cx, cy - (badgeText.ascent() + badgeText.descent()) / 2f, badgeText);
        }

        if (editing) {
            float mr = Ui.dp(11);
            float cx = iconRect.left + Ui.dp(3), cy = iconRect.top + Ui.dp(3);
            minusRect.set(cx - mr * 1.8f, cy - mr * 1.8f, cx + mr * 1.8f, cy + mr * 1.8f);
            c.drawCircle(cx, cy, mr, minusBg);
            c.drawLine(cx - mr * 0.45f, cy, cx + mr * 0.45f, cy, minusFg);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!editing) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragStarted = false;
                downOnMinus = minusRect.contains(downX, downY);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!downOnMinus && !dragStarted
                        && Math.hypot(e.getX() - downX, e.getY() - downY) > slop) {
                    dragStarted = true;
                    cb.onIconStartDrag(this);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (downOnMinus && minusRect.contains(e.getX(), e.getY())) cb.onIconRemove(this);
                return true;
            default:
                return true;
        }
    }
}
