package in.shazverse.srklauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.io.File;

/**
 * The launcher's own wallpaper plus a pre-blurred copy, used to draw real "frosted glass"
 * behind the Dock, folders and panels — the look iOS is known for.
 */
final class Glass {
    private Glass() {}

    static final String[] PRESET_NAMES = {"Aurora", "Sunset", "Ocean", "Graphite", "Mint", "Dusk"};
    private static final int[][] PRESETS = {
            {0xFF0B1E4F, 0xFF3D5AFE, 0xFFB388FF, 0xFF00B8D4, 0xFF1A237E},
            {0xFF2A0845, 0xFFFF6A3D, 0xFFFFB86C, 0xFFE84393, 0xFF6C1E5A},
            {0xFF012A4A, 0xFF00A6FB, 0xFF0582CA, 0xFF7DE2D1, 0xFF003554},
            {0xFF141416, 0xFF3A3A40, 0xFF5E5E66, 0xFF2C2C30, 0xFF0A0A0B},
            {0xFF0B3D2E, 0xFF34C759, 0xFF9BE15D, 0xFF00C2A8, 0xFF07261C},
            {0xFF1B1035, 0xFF5E5CE6, 0xFFFF7EB3, 0xFFFFC371, 0xFF2E1A47},
    };

    /** Wallpaper cropped to the screen's aspect ratio; null = use the phone's own wallpaper. */
    static volatile Bitmap wallpaper;
    /** Small, heavily blurred copy of {@link #wallpaper}. */
    static volatile Bitmap blurred;
    static int screenW = 1, screenH = 1;

    static String mode(SharedPreferences p) {
        return p.getString("wp", "preset:0");
    }

    static File photoFile(Context c) {
        return new File(c.getFilesDir(), "wallpaper.jpg");
    }

    /** Background thread: builds the wallpaper and its blurred copy. */
    static void load(Context c, SharedPreferences p, int w, int h) {
        screenW = Math.max(1, w);
        screenH = Math.max(1, h);
        String m = mode(p);
        Bitmap wp = null;
        try {
            if (m.startsWith("preset:")) {
                wp = preset(Integer.parseInt(m.substring(7)), screenW / 2, screenH / 2);
            } else if (m.equals("photo")) {
                wp = photo(photoFile(c), screenW, screenH);
                if (wp == null) wp = preset(0, screenW / 2, screenH / 2);
            }
        } catch (Throwable t) {
            wp = null;
        }
        Bitmap bl = wp == null ? null : blur(wp, Math.max(24, screenW / 10));
        wallpaper = wp;
        blurred = bl;
    }

    static Bitmap preset(int idx, int w, int h) {
        int[] c = PRESETS[Math.max(0, Math.min(idx, PRESETS.length - 1))];
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(c[0]);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float[][] blobs = {
                {0.15f, 0.18f, 0.75f}, {0.9f, 0.35f, 0.7f}, {0.3f, 0.75f, 0.8f}, {0.85f, 0.95f, 0.65f}
        };
        for (int i = 0; i < blobs.length; i++) {
            float cx = blobs[i][0] * w, cy = blobs[i][1] * h, r = blobs[i][2] * Math.max(w, h);
            int col = c[1 + i];
            p.setShader(new RadialGradient(cx, cy, r, new int[]{col, col & 0x00FFFFFF},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, r, p);
        }
        return b;
    }

    static Bitmap photo(File f, int w, int h) {
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (src == null) return null;
        return cropTo(src, w, h);
    }

    static Bitmap cropTo(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float s = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((w - src.getWidth() * s) / 2f, (h - src.getHeight() * s) / 2f);
        c.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** Downscale + 3-pass box blur (≈ gaussian). */
    static Bitmap blur(Bitmap src, int targetW) {
        int tw = Math.max(8, targetW);
        int th = Math.max(8, Math.round(tw * src.getHeight() / (float) src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, tw, th, true);
        if (small.getConfig() != Bitmap.Config.ARGB_8888 || !small.isMutable()) {
            small = small.copy(Bitmap.Config.ARGB_8888, true);
        }
        int[] px = new int[tw * th];
        small.getPixels(px, 0, tw, 0, 0, tw, th);
        int r = Math.max(2, tw / 28);
        for (int pass = 0; pass < 3; pass++) {
            boxH(px, tw, th, r);
            boxV(px, tw, th, r);
        }
        small.setPixels(px, 0, tw, 0, 0, tw, th);
        return small;
    }

    private static void boxH(int[] px, int w, int h, int r) {
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int off = y * w;
            long a = 0, rr = 0, g = 0, b = 0;
            int n = 0;
            for (int x = -r; x <= r; x++) {
                int c = px[off + Math.max(0, Math.min(w - 1, x))];
                a += c >>> 24; rr += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
            }
            for (int x = 0; x < w; x++) {
                row[x] = (int) (((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (b / n));
                int out = px[off + Math.max(0, Math.min(w - 1, x - r))];
                int in = px[off + Math.max(0, Math.min(w - 1, x + r + 1))];
                a += (in >>> 24) - (out >>> 24);
                rr += ((in >> 16) & 255) - ((out >> 16) & 255);
                g += ((in >> 8) & 255) - ((out >> 8) & 255);
                b += (in & 255) - (out & 255);
            }
            System.arraycopy(row, 0, px, off, w);
        }
    }

    private static void boxV(int[] px, int w, int h, int r) {
        int[] col = new int[h];
        for (int x = 0; x < w; x++) {
            long a = 0, rr = 0, g = 0, b = 0;
            int n = 0;
            for (int y = -r; y <= r; y++) {
                int c = px[Math.max(0, Math.min(h - 1, y)) * w + x];
                a += c >>> 24; rr += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
            }
            for (int y = 0; y < h; y++) {
                col[y] = (int) (((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (b / n));
                int out = px[Math.max(0, Math.min(h - 1, y - r)) * w + x];
                int in = px[Math.max(0, Math.min(h - 1, y + r + 1)) * w + x];
                a += (in >>> 24) - (out >>> 24);
                rr += ((in >> 16) & 255) - ((out >> 16) & 255);
                g += ((in >> 8) & 255) - ((out >> 8) & 255);
                b += (in & 255) - (out & 255);
            }
            for (int y = 0; y < h; y++) px[y * w + x] = col[y];
        }
    }

    // ------------------------------------------------------------ drawing

    private static final Paint SHADE = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint TINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint EDGE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Matrix M = new Matrix();
    private static final int[] LOC = new int[2];
    private static Bitmap shaderFor;
    private static BitmapShader shader;

    static {
        EDGE.setStyle(Paint.Style.STROKE);
        EDGE.setColor(0x33FFFFFF);
    }

    /**
     * Draws a frosted-glass rounded rectangle into {@code host}'s canvas.
     * {@code fallback} is used when the phone's own wallpaper is active (no blur source).
     */
    static void drawRound(Canvas c, View host, RectF r, float radius, int tint, int fallback) {
        Bitmap bl = blurred;
        if (bl != null && !bl.isRecycled()) {
            if (shaderFor != bl) {
                shader = new BitmapShader(bl, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                shaderFor = bl;
            }
            host.getLocationOnScreen(LOC);
            float s = screenW / (float) bl.getWidth();
            M.setScale(s, s);
            M.postTranslate(-LOC[0], -LOC[1]);
            shader.setLocalMatrix(M);
            SHADE.setShader(shader);
            c.drawRoundRect(r, radius, radius, SHADE);
            TINT.setColor(tint);
        } else {
            TINT.setColor(fallback);
        }
        c.drawRoundRect(r, radius, radius, TINT);
        EDGE.setStrokeWidth(Math.max(1f, Ui.dp(0.6f)));
        c.drawRoundRect(r, radius, radius, EDGE);
    }

    /** Glass background for ordinary views (Dock, panels, search pill…). */
    static final class GlassDrawable extends Drawable {
        private final View host;
        private final float radius;
        private final int tint, fallback;
        private final RectF r = new RectF();

        GlassDrawable(View host, float radiusPx, int tint, int fallback) {
            this.host = host;
            this.radius = radiusPx;
            this.tint = tint;
            this.fallback = fallback;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            r.set(b);
            drawRound(c, host, r, radius, tint, fallback);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Draws the launcher wallpaper full-screen (centre-cropped). */
    static final class WallpaperView extends View {
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect dst = new Rect();

        WallpaperView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            Bitmap wp = wallpaper;
            if (wp == null || wp.isRecycled()) return;
            dst.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(wp, null, dst, p);
        }
    }
}
