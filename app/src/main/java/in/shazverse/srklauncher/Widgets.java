package in.shazverse.srklauncher;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.BatteryManager;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Home-screen widgets: Clock, Calendar, Battery and Weather. */
final class Widgets {
    private Widgets() {}

    static abstract class Card extends View {
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF box = new RectF();
        final float radius;

        Card(Context c) {
            super(c);
            bg.setColor(Ui.CARD);
            radius = Ui.dp(22);
            setClickable(true);
        }

        void drawCard(Canvas c) {
            box.set(0, 0, getWidth(), getHeight());
            if (bg.getShader() == null) Glass.drawRound(c, this, box, radius, 0xB31C1C1E, Ui.CARD);
            else c.drawRoundRect(box, radius, radius, bg);
        }

        @Override
        public void setPressed(boolean p) {
            super.setPressed(p);
            animate().scaleX(p ? 0.96f : 1f).scaleY(p ? 0.96f : 1f).setDuration(140).start();
        }

        LauncherActivity act() {
            return (LauncherActivity) getContext();
        }

        static String fit(String s, TextPaint p, float w) {
            if (s == null) return "";
            return TextUtils.ellipsize(s, p, Math.max(0, w), TextUtils.TruncateAt.END).toString();
        }
    }

    // ------------------------------------------------------------------ Clock

    static final class ClockCard extends Card {
        private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hand = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sec = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint num;

        ClockCard(Context c) {
            super(c);
            face.setColor(Color.WHITE);
            tick.setStrokeCap(Paint.Cap.ROUND);
            hand.setColor(0xFF1C1C1E);
            hand.setStrokeCap(Paint.Cap.ROUND);
            sec.setColor(Ui.ORANGE);
            sec.setStrokeCap(Paint.Cap.ROUND);
            num = Ui.paint(0xFF1C1C1E, 12, Ui.MEDIUM);
            num.setTextAlign(Paint.Align.CENTER);
            setContentDescription("Clock");
            setOnClickListener(v -> act().openIntent(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        }

        @Override
        protected void onDraw(Canvas c) {
            drawCard(c);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f - Ui.dp(12);
            c.drawCircle(cx, cy, r, face);
            for (int i = 0; i < 60; i++) {
                double a = Math.toRadians(i * 6);
                boolean major = i % 5 == 0;
                float r1 = r * (major ? 0.86f : 0.92f), r2 = r * 0.97f;
                tick.setStrokeWidth(major ? r * 0.03f : r * 0.012f);
                tick.setColor(major ? 0xFF1C1C1E : 0xFF8E8E93);
                float s = (float) Math.sin(a), co = (float) Math.cos(a);
                c.drawLine(cx + s * r1, cy - co * r1, cx + s * r2, cy - co * r2, tick);
            }
            num.setTextSize(r * 0.2f);
            for (int h = 1; h <= 12; h++) {
                double a = Math.toRadians(h * 30);
                float rr = r * 0.68f;
                c.drawText(String.valueOf(h), cx + (float) Math.sin(a) * rr,
                        cy - (float) Math.cos(a) * rr - (num.ascent() + num.descent()) / 2f, num);
            }
            Calendar now = Calendar.getInstance();
            int s = now.get(Calendar.SECOND);
            float m = now.get(Calendar.MINUTE) + s / 60f;
            float hr = now.get(Calendar.HOUR) + m / 60f;
            hand.setStrokeWidth(r * 0.065f);
            drawHand(c, cx, cy, hr * 30, r * 0.5f, hand);
            hand.setStrokeWidth(r * 0.045f);
            drawHand(c, cx, cy, m * 6, r * 0.78f, hand);
            sec.setStrokeWidth(r * 0.02f);
            drawHand(c, cx, cy, s * 6, r * 0.86f, sec);
            c.drawCircle(cx, cy, r * 0.05f, sec);
            if (isAttachedToWindow()) postInvalidateDelayed(1000 - now.get(Calendar.MILLISECOND));
        }

        private static void drawHand(Canvas c, float cx, float cy, float deg, float len, Paint p) {
            double a = Math.toRadians(deg);
            float s = (float) Math.sin(a), co = (float) Math.cos(a);
            c.drawLine(cx - s * len * 0.15f, cy + co * len * 0.15f, cx + s * len, cy - co * len, p);
        }
    }

    // --------------------------------------------------------------- Calendar

    static final class CalendarCard extends Card {
        private final TextPaint day, date, title, sub;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tmp = new RectF();
        private String evTitle, evTime;
        private boolean hasPerm;

        CalendarCard(Context c) {
            super(c);
            day = Ui.paint(Ui.RED, 12, Ui.BOLD);
            date = Ui.paint(Ui.WHITE, 40, Ui.LIGHT);
            title = Ui.paint(Ui.WHITE, 13, Ui.MEDIUM);
            sub = Ui.paint(Ui.GREY, 12, Ui.REGULAR);
            bar.setColor(Ui.RED);
            setContentDescription("Calendar");
            setOnClickListener(v -> {
                if (!hasPerm) {
                    act().requestPerm(Manifest.permission.READ_CALENDAR, LauncherActivity.REQ_CALENDAR);
                } else {
                    Uri.Builder b = CalendarContract.CONTENT_URI.buildUpon().appendPath("time");
                    ContentUris.appendId(b, System.currentTimeMillis());
                    act().openIntent(new Intent(Intent.ACTION_VIEW, b.build()));
                }
            });
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            load();
        }

        void load() {
            hasPerm = getContext().checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
            evTitle = null;
            evTime = null;
            if (hasPerm) {
                long now = System.currentTimeMillis();
                Calendar end = Calendar.getInstance();
                end.set(Calendar.HOUR_OF_DAY, 23);
                end.set(Calendar.MINUTE, 59);
                end.set(Calendar.SECOND, 59);
                Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
                ContentUris.appendId(b, now);
                ContentUris.appendId(b, end.getTimeInMillis());
                String[] cols = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY};
                try (Cursor cur = getContext().getContentResolver().query(b.build(), cols,
                        CalendarContract.Instances.END + " >= ?", new String[]{String.valueOf(now)},
                        CalendarContract.Instances.BEGIN + " ASC")) {
                    if (cur != null && cur.moveToFirst()) {
                        evTitle = cur.getString(0);
                        boolean allDay = cur.getInt(2) == 1;
                        evTime = allDay ? "All day" : DateFormat.getTimeFormat(getContext()).format(new Date(cur.getLong(1)));
                    }
                } catch (Exception ignored) {
                }
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            drawCard(c);
            float pad = Ui.dp(14);
            float w = getWidth() - pad * 2;
            Date d = new Date();
            String dn = new SimpleDateFormat("EEEE", Locale.getDefault()).format(d).toUpperCase(Locale.getDefault());
            float y = pad - day.ascent();
            c.drawText(fit(dn, day, w), pad, y, day);
            y += day.descent() - date.ascent();
            c.drawText(new SimpleDateFormat("d", Locale.getDefault()).format(d), pad, y, date);
            y += date.descent() + Ui.dp(6);
            if (evTitle != null) {
                float lineH = title.getTextSize() * 1.3f;
                tmp.set(pad, y, pad + Ui.dp(3), y + lineH * 2);
                c.drawRoundRect(tmp, Ui.dp(2), Ui.dp(2), bar);
                c.drawText(fit(evTitle, title, w - Ui.dp(9)), pad + Ui.dp(9), y - title.ascent(), title);
                c.drawText(fit(evTime, sub, w - Ui.dp(9)), pad + Ui.dp(9), y + lineH - sub.ascent(), sub);
            } else {
                String msg = hasPerm ? "No more events today" : "Tap to show events";
                c.drawText(fit(msg, sub, w), pad, y - sub.ascent(), sub);
            }
        }
    }

    // ---------------------------------------------------------------- Battery

    static final class BatteryCard extends Card {
        private int level = -1;
        private boolean charging;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint pct, name, big, sub;
        private final RectF oval = new RectF();
        private final BroadcastReceiver rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                update(i);
            }
        };

        BatteryCard(Context c) {
            super(c);
            track.setStyle(Paint.Style.STROKE);
            track.setColor(0x33FFFFFF);
            track.setStrokeCap(Paint.Cap.ROUND);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
            pct = Ui.paint(Ui.WHITE, 13, Ui.MEDIUM);
            pct.setTextAlign(Paint.Align.CENTER);
            name = Ui.paint(Ui.WHITE, 15, Ui.MEDIUM);
            big = Ui.paint(Ui.WHITE, 34, Ui.LIGHT);
            sub = Ui.paint(Ui.GREY, 13, Ui.REGULAR);
            setContentDescription("Battery");
            setOnClickListener(v -> act().openIntent(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)));
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            Intent sticky = getContext().registerReceiver(rx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) update(sticky);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            try {
                getContext().unregisterReceiver(rx);
            } catch (Exception ignored) {
            }
        }

        private void update(Intent i) {
            int l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            level = (s > 0 && l >= 0) ? Math.round(l * 100f / s) : l;
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            drawCard(c);
            float pad = Ui.dp(16);
            float h = getHeight();
            if (getWidth() < h * 1.5f) {
                drawCompact(c, pad);
                return;
            }
            float r = (h - pad * 2) / 2f * 0.82f;
            float cx = pad + r + Ui.dp(4), cy = h / 2f;
            float sw = r * 0.2f;
            track.setStrokeWidth(sw);
            arc.setStrokeWidth(sw);
            arc.setColor(level >= 0 && level <= 20 && !charging ? Ui.RED : Ui.GREEN);
            oval.set(cx - r, cy - r, cx + r, cy + r);
            c.drawCircle(cx, cy, r, track);
            if (level > 0) c.drawArc(oval, -90, 360f * level / 100f, false, arc);
            pct.setTextSize(r * 0.5f);
            String center = charging ? "⚡" : (level >= 0 ? level + "" : "–");
            c.drawText(center, cx, cy - (pct.ascent() + pct.descent()) / 2f, pct);

            float tx = cx + r + Ui.dp(22);
            float tw = getWidth() - tx - pad;
            float y = cy - Ui.dp(26);
            c.drawText(fit("Phone", name, tw), tx, y, name);
            y += Ui.dp(4) - big.ascent();
            c.drawText(level >= 0 ? level + "%" : "–", tx, y, big);
            y += big.descent() - sub.ascent();
            c.drawText(fit(charging ? "Charging" : "On battery", sub, tw), tx, y, sub);
        }

        private void drawCompact(Canvas c, float pad) {
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h - pad * 2 - Ui.dp(20)) / 2f * 0.8f;
            float cx = w / 2f, cy = pad + r + Ui.dp(4);
            float sw = r * 0.16f;
            track.setStrokeWidth(sw);
            arc.setStrokeWidth(sw);
            arc.setColor(level >= 0 && level <= 20 && !charging ? Ui.RED : Ui.GREEN);
            oval.set(cx - r, cy - r, cx + r, cy + r);
            c.drawCircle(cx, cy, r, track);
            if (level > 0) c.drawArc(oval, -90, 360f * level / 100f, false, arc);
            pct.setTextSize(r * 0.42f);
            String center = level >= 0 ? level + "%" : "–";
            c.drawText(center, cx, cy - (pct.ascent() + pct.descent()) / 2f, pct);
            sub.setTextAlign(Paint.Align.CENTER);
            c.drawText(charging ? "Charging" : "Phone", cx, h - pad, sub);
            sub.setTextAlign(Paint.Align.LEFT);
        }
    }

    // ---------------------------------------------------------------- Weather

    static final class WeatherCard extends Card implements Runnable {
        private final TextPaint city, temp, cond, small;
        private final Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cloud = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint drop = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int shaderH = -1;
        private boolean shaderDay;

        WeatherCard(Context c) {
            super(c);
            city = Ui.paint(Ui.WHITE, 16, Ui.MEDIUM);
            temp = Ui.paint(Ui.WHITE, 44, Ui.LIGHT);
            cond = Ui.paint(Ui.WHITE, 14, Ui.MEDIUM);
            small = Ui.paint(0xE6FFFFFF, 13, Ui.REGULAR);
            sun.setColor(Ui.YELLOW);
            cloud.setColor(0xFFF2F2F7);
            drop.setColor(0xFF64D2FF);
            drop.setStrokeWidth(Ui.dp(2.5f));
            drop.setStrokeCap(Paint.Cap.ROUND);
            setContentDescription("Weather");
            setOnClickListener(v -> {
                if (!Weather.hasPermission(getContext())) {
                    act().requestPerm(Manifest.permission.ACCESS_COARSE_LOCATION, LauncherActivity.REQ_LOCATION);
                } else {
                    Weather.refresh(getContext(), true);
                }
            });
        }

        @Override
        public void run() {
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            Weather.listeners.add(this);
            Weather.refresh(getContext(), false);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            Weather.listeners.remove(this);
        }

        @Override
        protected void onDraw(Canvas c) {
            boolean day = !Weather.hasData || Weather.isDay;
            if (shaderH != getHeight() || shaderDay != day) {
                bg.setShader(new LinearGradient(0, 0, 0, getHeight(),
                        day ? 0xFF2F7FD0 : 0xFF0B1631, day ? 0xFF6BB2EE : 0xFF2B3E6E, Shader.TileMode.CLAMP));
                shaderH = getHeight();
                shaderDay = day;
            }
            drawCard(c);
            float pad = Ui.dp(16);
            float w = getWidth(), h = getHeight();
            float iconS = Math.min(h * 0.36f, Ui.dp(52));
            if (!Weather.hasData) {
                c.drawText("Weather", pad, pad - city.ascent(), city);
                drawCondition(c, 0, true, w - pad - iconS, pad, iconS);
                c.drawText(fit(Weather.status, small, w - pad * 2), pad, h - pad - small.descent(), small);
                return;
            }
            float y = pad - city.ascent();
            c.drawText(fit(Weather.city, city, w - pad * 3 - iconS), pad, y, city);
            y += city.descent() - temp.ascent();
            c.drawText(Math.round(Weather.temp) + "°", pad, y, temp);
            drawCondition(c, Weather.code, Weather.isDay, w - pad - iconS, pad, iconS);
            float by = h - pad - small.descent();
            c.drawText("H:" + Math.round(Weather.hi) + "°  L:" + Math.round(Weather.lo) + "°", pad, by, small);
            c.drawText(fit(Weather.describe(Weather.code), cond, w - pad * 2), pad, by - small.getTextSize() * 1.35f, cond);
        }

        private void drawCondition(Canvas c, int code, boolean day, float l, float t, float s) {
            float cx = l + s / 2f, cy = t + s / 2f;
            boolean clear = code == 0 || code == 1;
            boolean partly = code == 2;
            if (clear || partly) {
                if (day) {
                    c.drawCircle(partly ? cx - s * 0.12f : cx, partly ? cy - s * 0.12f : cy, s * (partly ? 0.26f : 0.32f), sun);
                } else {
                    Path moon = new Path();
                    moon.addCircle(cx, cy, s * 0.3f, Path.Direction.CW);
                    Path cut = new Path();
                    cut.addCircle(cx + s * 0.15f, cy - s * 0.12f, s * 0.26f, Path.Direction.CW);
                    moon.op(cut, Path.Op.DIFFERENCE);
                    c.drawPath(moon, cloud);
                }
                if (clear) return;
            }
            // cloud
            float cw = s * 0.9f;
            float base = cy + s * 0.12f;
            c.drawCircle(cx - cw * 0.22f, base - s * 0.02f, s * 0.17f, cloud);
            c.drawCircle(cx + s * 0.02f, base - s * 0.14f, s * 0.24f, cloud);
            c.drawCircle(cx + cw * 0.27f, base - s * 0.01f, s * 0.15f, cloud);
            c.drawRoundRect(cx - cw * 0.4f, base - s * 0.04f, cx + cw * 0.42f, base + s * 0.14f, s * 0.09f, s * 0.09f, cloud);
            boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
            boolean snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
            if (rain) {
                for (int i = -1; i <= 1; i++) {
                    float x = cx + i * s * 0.2f;
                    c.drawLine(x, base + s * 0.22f, x - s * 0.06f, base + s * 0.36f, drop);
                }
            } else if (snow) {
                for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * s * 0.2f, base + s * 0.3f, s * 0.04f, cloud);
            } else if (code >= 95) {
                Path bolt = new Path();
                bolt.moveTo(cx + s * 0.04f, base + s * 0.16f);
                bolt.lineTo(cx - s * 0.1f, base + s * 0.34f);
                bolt.lineTo(cx, base + s * 0.34f);
                bolt.lineTo(cx - s * 0.06f, base + s * 0.5f);
                bolt.lineTo(cx + s * 0.12f, base + s * 0.28f);
                bolt.lineTo(cx + s * 0.02f, base + s * 0.28f);
                bolt.close();
                c.drawPath(bolt, sun);
            } else if (code == 45 || code == 48) {
                for (int i = 0; i < 2; i++) {
                    float yy = base + s * (0.24f + i * 0.1f);
                    c.drawLine(cx - s * 0.3f, yy, cx + s * 0.3f, yy, cloud);
                }
            }
        }
    }

    /** Small helper used by the Settings list. */
    static Intent writeSettingsIntent(Context c) {
        return new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.getPackageName()));
    }
}
