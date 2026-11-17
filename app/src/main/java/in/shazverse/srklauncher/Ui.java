package in.shazverse.srklauncher;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

/** Small UI helpers shared by every screen. */
final class Ui {
    private Ui() {}

    static DisplayMetrics dm;

    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);

    static final int WHITE = 0xFFFFFFFF;
    static final int BLUE = 0xFF0A84FF;
    static final int GREEN = 0xFF30D158;
    static final int ORANGE = 0xFFFF9F0A;
    static final int RED = 0xFFFF453A;
    static final int PURPLE = 0xFF5E5CE6;
    static final int YELLOW = 0xFFFFD60A;
    static final int GREY = 0xFF8E8E93;
    static final int CARD = 0xD91C1C1E;
    static final int TILE = 0x99303034;
    static final int FILL = 0x52787880;

    static void init(Context c) {
        dm = c.getResources().getDisplayMetrics();
    }

    static int dp(float v) {
        return Math.round(v * dm.density);
    }

    static float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm);
    }

    static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    static TextPaint paint(int color, float spSize, Typeface tf) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(sp(spSize));
        p.setTypeface(tf);
        return p;
    }

    static TextView text(Context c, CharSequence s, float spSize, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, spSize);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    static Drawable icon(Context c, int res, int tint) {
        Drawable d = c.getDrawable(res).mutate();
        d.setTint(tint);
        return d;
    }

    static EditText searchField(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(0x99FFFFFF);
        e.setTextColor(WHITE);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setBackground(round(FILL, 12));
        e.setGravity(Gravity.CENTER_VERTICAL);
        e.setPadding(dp(10), 0, dp(10), 0);
        Drawable d = icon(c, R.drawable.ic_search, 0x99FFFFFF);
        d.setBounds(0, 0, dp(20), dp(20));
        e.setCompoundDrawablesRelative(d, null, null, null);
        e.setCompoundDrawablePadding(dp(6));
        return e;
    }

    /** Real blur of the home screen behind an overlay (Android 12+). */
    static void blur(View v, boolean on) {
        if (Build.VERSION.SDK_INT >= 31) {
            v.setRenderEffect(on ? RenderEffect.createBlurEffect(dp(20), dp(20), Shader.TileMode.CLAMP) : null);
        }
    }
}
