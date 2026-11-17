package in.shazverse.srklauncher;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/** iOS lock-screen style Notification Centre (swipe down from the top-left). */
final class NotificationCentre extends FrameLayout implements Motion.OverlayAnim {
    private final LauncherActivity act;
    private final LinearLayout col;
    private float downX, downY;

    NotificationCentre(LauncherActivity a) {
        super(a);
        act = a;
        setBackgroundColor(0x80000000);
        setClickable(true);

        col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Ui.dp(20), a.insetTop + Ui.dp(28), Ui.dp(20), a.insetBottom + Ui.dp(24));

        TextClock date = new TextClock(a);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        date.setTypeface(Ui.SEMIBOLD);
        date.setTextColor(0xE6FFFFFF);
        date.setGravity(Gravity.CENTER);
        col.addView(date, wrapW());

        TextClock time = new TextClock(a);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 104);
        time.setTypeface(Ui.BOLD);
        time.setTextColor(0xF2FFFFFF);
        time.setGravity(Gravity.CENTER);
        time.setIncludeFontPadding(false);
        time.setLetterSpacing(-0.03f);
        col.addView(time, wrapW());

        // Lock-screen style mini widgets
        LinearLayout chips = new LinearLayout(a);
        chips.setGravity(Gravity.CENTER);
        chips.setPadding(0, Ui.dp(16), 0, 0);
        chips.addView(chip(batteryText(a)));
        if (Weather.hasData) {
            chips.addView(chip(Math.round(Weather.temp) + "°  " + Weather.city));
        }
        col.addView(chips, wrapW());

        View spacer = new View(a);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        TextView none = Ui.text(a, "No Older Notifications", 15, 0x99FFFFFF, Ui.MEDIUM);
        none.setGravity(Gravity.CENTER);
        col.addView(none, wrapW());

        TextView open = Ui.text(a, "Show Android notifications", 15, Ui.WHITE, Ui.SEMIBOLD);
        open.setGravity(Gravity.CENTER);
        open.setBackground(Ui.round(0x33FFFFFF, 22));
        open.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), Ui.dp(12));
        open.setOnClickListener(v -> {
            a.closeOverlayNow();
            a.expandNotifications();
        });
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ol.topMargin = Ui.dp(14);
        ol.bottomMargin = Ui.dp(22);
        col.addView(open, ol);

        View handle = new View(a);
        handle.setBackground(Ui.round(0xB3FFFFFF, 3));
        col.addView(handle, new LinearLayout.LayoutParams(Ui.dp(134), Ui.dp(5)));

        addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static LinearLayout.LayoutParams wrapW() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView chip(String s) {
        TextView t = Ui.text(getContext(), s, 15, Ui.WHITE, Ui.SEMIBOLD);
        t.setBackground(Ui.round(0x33FFFFFF, 18));
        t.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        t.setSingleLine(true);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.leftMargin = l.rightMargin = Ui.dp(5);
        t.setLayoutParams(l);
        return t;
    }

    private static String batteryText(Context c) {
        Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return "Battery";
        int l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean ch = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
        int pct = s > 0 && l >= 0 ? Math.round(l * 100f / s) : l;
        return (ch ? "⚡ " : "🔋 ") + pct + "%";
    }

    // Swipe up anywhere closes it.
    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        return handleSwipe(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        handleSwipe(e);
        return true;
    }

    private boolean handleSwipe(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float dy = e.getY() - downY;
            if (dy < -Ui.dp(40) && Math.abs(dy) > Math.abs(e.getX() - downX)) {
                act.closeOverlay();
                return true;
            }
        }
        return false;
    }

    @Override
    public void enter() {
        setAlpha(0f);
        animate().alpha(1f).setDuration(220).start();
        col.setTranslationY(-act.lastH * 0.35f);
        col.animate().translationY(0).setInterpolator(Motion.EASE_OUT).setDuration(420).start();
    }

    @Override
    public void exit(Runnable done) {
        col.animate().translationY(-act.lastH * 0.4f).setInterpolator(Motion.EASE_IN).setDuration(260).start();
        animate().alpha(0f).setDuration(280).withEndAction(done).start();
    }
}
