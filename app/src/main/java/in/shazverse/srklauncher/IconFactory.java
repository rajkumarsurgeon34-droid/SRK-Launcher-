package in.shazverse.srklauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

/** Turns any app icon into an iOS-style "squircle" icon. */
final class IconFactory {
    private IconFactory() {}

    /** Superellipse (n = 5) — close to Apple's continuous-corner icon shape. */
    static Path squircle(float size) {
        Path p = new Path();
        float r = size / 2f;
        int steps = 120;
        double n = 5.0;
        for (int i = 0; i < steps; i++) {
            double t = 2 * Math.PI * i / steps;
            double c = Math.cos(t), s = Math.sin(t);
            float x = (float) (r + r * Math.signum(c) * Math.pow(Math.abs(c), 2 / n));
            float y = (float) (r + r * Math.signum(s) * Math.pow(Math.abs(s), 2 / n));
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
        return p;
    }

    static Bitmap make(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            int inset = size / 4;
            Drawable bg = a.getBackground();
            Drawable fg = a.getForeground();
            if (bg != null) {
                bg.setBounds(-inset, -inset, size + inset, size + inset);
                bg.draw(c);
            } else {
                c.drawColor(Color.WHITE);
            }
            if (fg != null) {
                fg.setBounds(-inset, -inset, size + inset, size + inset);
                fg.draw(c);
            }
        } else if (d != null) {
            c.drawColor(Color.WHITE);
            int pad = Math.round(size * 0.12f);
            d.setBounds(pad, pad, size - pad, size - pad);
            d.draw(c);
        } else {
            c.drawColor(0xFF3A3A3C);
        }
        // Cut everything outside the squircle (anti-aliased edge).
        Path outside = squircle(size);
        outside.setFillType(Path.FillType.INVERSE_WINDING);
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawPath(outside, clear);
        return b;
    }
}
