package in.shazverse.srklauncher;

import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** iOS-style Control Centre: connectivity, media, toggles, brightness and volume sliders, shortcuts. */
final class ControlCenter extends FrameLayout implements Motion.OverlayAnim {
    private LinearLayout content;
    private final LauncherActivity act;
    private final CircleButton airplane, cellular, wifi, bluetooth;
    private final TileButton rotation, dnd, mirror, torch, timer, calc, camera;
    private final VSlider brightness, volume;
    private final TextView mediaTitle, mediaArtist;
    private final ImageView playBtn;
    private MediaController controller;
    private MediaController.Callback mediaCb;
    private float downX, downY;

    ControlCenter(LauncherActivity a) {
        super(a);
        act = a;
        setBackgroundColor(0x66000000);
        setClickable(true);
        setOnClickListener(v -> a.closeOverlay());

        int margin = Ui.dp(24), gap = Ui.dp(14);
        int unit = (a.lastW - margin * 2 - gap * 3) / 4;
        int in = gap / 2;

        IconGrid grid = new IconGrid(a, 4, 5);
        grid.fixedCell = unit + gap;
        grid.setPadding(margin - in, 0, margin - in, 0);

        // Connectivity block (2x2)
        IconGrid conn = new IconGrid(a, 2, 2);
        conn.setBackground(Ui.round(Ui.TILE, 28));
        conn.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));
        conn.setClickable(true);
        airplane = new CircleButton(a, R.drawable.ic_airplane, Ui.ORANGE);
        cellular = new CircleButton(a, R.drawable.ic_cellular, Ui.GREEN);
        wifi = new CircleButton(a, R.drawable.ic_wifi, Ui.BLUE);
        bluetooth = new CircleButton(a, R.drawable.ic_bluetooth, Ui.BLUE);
        conn.add(airplane, 0, 0, 1, 1, 0);
        conn.add(cellular, 1, 0, 1, 1, 0);
        conn.add(wifi, 0, 1, 1, 1, 0);
        conn.add(bluetooth, 1, 1, 1, 1, 0);
        grid.add(conn, 0, 0, 2, 2, in);

        // Media block (2x2)
        LinearLayout media = new LinearLayout(a);
        media.setOrientation(LinearLayout.VERTICAL);
        media.setBackground(Ui.round(Ui.TILE, 28));
        media.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(10));
        media.setClickable(true);
        mediaTitle = Ui.text(a, "Not Playing", 15, Ui.WHITE, Ui.MEDIUM);
        mediaTitle.setSingleLine(true);
        mediaTitle.setEllipsize(TextUtils.TruncateAt.END);
        mediaArtist = Ui.text(a, "", 13, 0xB3FFFFFF, Ui.REGULAR);
        mediaArtist.setMaxLines(2);
        mediaArtist.setEllipsize(TextUtils.TruncateAt.END);
        media.addView(mediaTitle);
        media.addView(mediaArtist);
        media.addView(new View(a), new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout controls = new LinearLayout(a);
        controls.setGravity(Gravity.CENTER);
        ImageView prev = mediaButton(R.drawable.ic_prev);
        playBtn = mediaButton(R.drawable.ic_play);
        ImageView next = mediaButton(R.drawable.ic_next);
        controls.addView(prev, new LinearLayout.LayoutParams(0, Ui.dp(40), 1));
        controls.addView(playBtn, new LinearLayout.LayoutParams(0, Ui.dp(40), 1));
        controls.addView(next, new LinearLayout.LayoutParams(0, Ui.dp(40), 1));
        media.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        grid.add(media, 2, 0, 2, 2, in);

        rotation = new TileButton(a, R.drawable.ic_rotation_lock, Ui.RED, null);
        dnd = new TileButton(a, R.drawable.ic_moon, Ui.PURPLE, null);
        brightness = new VSlider(a, R.drawable.ic_sun);
        volume = new VSlider(a, R.drawable.ic_volume);
        mirror = new TileButton(a, R.drawable.ic_mirror, Ui.BLUE, "Screen\nMirroring");
        torch = new TileButton(a, R.drawable.ic_torch, Ui.BLUE, null);
        timer = new TileButton(a, R.drawable.ic_timer, Ui.ORANGE, null);
        calc = new TileButton(a, R.drawable.ic_calc, Ui.ORANGE, null);
        camera = new TileButton(a, R.drawable.ic_camera, Ui.BLUE, null);
        grid.add(rotation, 0, 2, 1, 1, in);
        grid.add(dnd, 1, 2, 1, 1, in);
        grid.add(brightness, 2, 2, 1, 2, in);
        grid.add(volume, 3, 2, 1, 2, in);
        grid.add(mirror, 0, 3, 2, 1, in);
        grid.add(torch, 0, 4, 1, 1, in);
        grid.add(timer, 1, 4, 1, 1, in);
        grid.add(calc, 2, 4, 1, 1, in);
        grid.add(camera, 3, 4, 1, 1, in);

        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, a.insetTop + Ui.dp(56), 0, 0);
        wrap.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView hint = Ui.text(a, "Swipe up to close", 12, 0x80FFFFFF, Ui.REGULAR);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(18), 0, 0);
        wrap.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(wrap, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content = wrap;

        wire();
        refresh();
    }

    @Override
    public void enter() {
        setAlpha(0f);
        animate().alpha(1f).setDuration(200).start();
        content.setPivotX(act.lastW * 0.85f);
        content.setPivotY(0);
        content.setScaleX(0.9f);
        content.setScaleY(0.9f);
        content.setTranslationY(-Ui.dp(40));
        content.animate().scaleX(1f).scaleY(1f).translationY(0)
                .setInterpolator(Motion.SPRING).setDuration(520).start();
    }

    @Override
    public void exit(Runnable done) {
        content.animate().scaleX(0.92f).scaleY(0.92f).translationY(-Ui.dp(50))
                .setInterpolator(Motion.EASE_IN).setDuration(200).start();
        animate().alpha(0f).setDuration(220).withEndAction(done).start();
    }

    private ImageView mediaButton(int res) {
        ImageView v = new ImageView(getContext());
        v.setImageDrawable(Ui.icon(getContext(), res, Ui.WHITE));
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));
        v.setClickable(true);
        return v;
    }

    private void wire() {
        final Context c = getContext();
        airplane.setOnClickListener(v -> act.openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
        cellular.setOnClickListener(v -> act.openSettings(Build.VERSION.SDK_INT >= 29
                ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_DATA_ROAMING_SETTINGS));
        wifi.setOnClickListener(v -> act.openSettings(Build.VERSION.SDK_INT >= 29
                ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS));
        bluetooth.setOnClickListener(v -> act.openSettings(Settings.ACTION_BLUETOOTH_SETTINGS));

        rotation.setOnClickListener(v -> {
            if (!Settings.System.canWrite(c)) {
                act.askWriteSettings();
                return;
            }
            ContentResolver cr = c.getContentResolver();
            int cur = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1);
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, cur == 1 ? 0 : 1);
            refresh();
        });
        dnd.setOnClickListener(v -> {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (!nm.isNotificationPolicyAccessGranted()) {
                act.openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                return;
            }
            boolean on = isDndOn(nm);
            nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            refresh();
        });
        brightness.onTouchStart = () -> {
            if (!Settings.System.canWrite(c)) act.toast("Allow \"Modify system settings\" for SRK Launcher to change brightness everywhere");
        };
        brightness.listener = val -> {
            if (Settings.System.canWrite(c)) {
                ContentResolver cr = c.getContentResolver();
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.round(val * 255)));
            } else {
                WindowManager.LayoutParams lp = act.getWindow().getAttributes();
                lp.screenBrightness = Math.max(0.02f, val);
                act.getWindow().setAttributes(lp);
            }
        };
        brightness.onTouchEnd = () -> {
            if (!Settings.System.canWrite(c)) act.askWriteSettings();
        };
        volume.listener = val -> {
            AudioManager am = c.getSystemService(AudioManager.class);
            if (am == null) return;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(val * max), 0);
            } catch (Exception ignored) {
            }
        };
        mirror.setOnClickListener(v -> act.openSettings("android.settings.CAST_SETTINGS"));
        torch.setOnClickListener(v -> {
            act.toggleTorch();
            torch.setActive(!act.torchOn);
        });
        timer.setOnClickListener(v -> {
            act.closeOverlay();
            act.openIntent(new Intent(AlarmClock.ACTION_SHOW_TIMERS));
        });
        calc.setOnClickListener(v -> {
            act.closeOverlay();
            act.openIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR));
        });
        camera.setOnClickListener(v -> {
            act.closeOverlay();
            act.openIntent(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
        });

        playBtn.setOnClickListener(v -> {
            mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            postDelayed(this::bindMedia, 400);
        });
    }

    /** Sends a media key to whatever app is playing — needs no special permission. */
    private void mediaKey(int code) {
        AudioManager am = getContext().getSystemService(AudioManager.class);
        if (am == null) return;
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code));
    }

    private static boolean isDndOn(NotificationManager nm) {
        int f = nm.getCurrentInterruptionFilter();
        return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
    }

    void bindMediaButtons(ImageView prev, ImageView next) {
        prev.setOnClickListener(v -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        next.setOnClickListener(v -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT));
    }

    void refresh() {
        Context c = getContext();
        ContentResolver cr = c.getContentResolver();
        airplane.setActive(Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi.setActive(wm != null && wm.isWifiEnabled());
        } catch (Exception e) {
            wifi.setActive(false);
        }
        try {
            BluetoothManager bm = c.getSystemService(BluetoothManager.class);
            BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
            bluetooth.setActive(ad != null && ad.isEnabled());
        } catch (Exception e) {
            bluetooth.setActive(false);
        }
        try {
            TelephonyManager tm = c.getSystemService(TelephonyManager.class);
            cellular.setActive(tm != null && tm.isDataEnabled());
        } catch (Exception e) {
            cellular.setActive(false);
        }
        rotation.setActive(Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 0);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        dnd.setActive(nm != null && isDndOn(nm));
        torch.setActive(act.torchOn);

        float br = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f;
        brightness.setValue(Math.max(0f, Math.min(1f, br)));
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am != null) {
            int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            volume.setValue(am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max);
        }
        bindMedia();
    }

    private void bindMedia() {
        AudioManager am = getContext().getSystemService(AudioManager.class);
        boolean playing = am != null && am.isMusicActive();
        mediaTitle.setText(playing ? "Playing" : "Not Playing");
        mediaArtist.setText(playing ? "Music or video in another app" : "Controls your music app");
        playBtn.setImageDrawable(Ui.icon(getContext(), playing ? R.drawable.ic_pause : R.drawable.ic_play, Ui.WHITE));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // prev/next live in the controls row next to play
        LinearLayout row = (LinearLayout) playBtn.getParent();
        bindMediaButtons((ImageView) row.getChildAt(0), (ImageView) row.getChildAt(2));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (controller != null && mediaCb != null) controller.unregisterCallback(mediaCb);
    }

    // Swipe up anywhere (outside sliders) closes Control Centre.
    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && isSwipeUp(e)) {
            act.closeOverlay();
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && isSwipeUp(e)) {
            act.closeOverlay();
            return true;
        }
        return super.onTouchEvent(e);
    }

    private boolean isSwipeUp(MotionEvent e) {
        float dy = e.getY() - downY;
        return dy < -Ui.dp(48) && Math.abs(dy) > Math.abs(e.getX() - downX);
    }

    // ------------------------------------------------------------ tiles

    static final class CircleButton extends View {
        private final Drawable icon;
        private final int activeColor;
        private boolean active;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        CircleButton(Context c, int res, int activeColor) {
            super(c);
            icon = c.getDrawable(res).mutate();
            this.activeColor = activeColor;
            setClickable(true);
        }

        void setActive(boolean a) {
            active = a;
            invalidate();
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f * 0.8f;
            p.setColor(active ? activeColor : 0x40FFFFFF);
            c.drawCircle(cx, cy, r, p);
            if (isPressed()) {
                p.setColor(0x33000000);
                c.drawCircle(cx, cy, r, p);
            }
            int s = Math.round(r * 0.95f);
            icon.setTint(Ui.WHITE);
            icon.setBounds(Math.round(cx - s / 2f), Math.round(cy - s / 2f), Math.round(cx + s / 2f), Math.round(cy + s / 2f));
            icon.draw(c);
        }
    }

    static final class TileButton extends View {
        private final Drawable icon;
        private final int activeColor;
        private final String[] lines;
        private boolean active;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint txt;
        private final RectF r = new RectF();

        TileButton(Context c, int res, int activeColor, String label) {
            super(c);
            icon = c.getDrawable(res).mutate();
            this.activeColor = activeColor;
            lines = label == null ? null : label.split("\n");
            txt = Ui.paint(Ui.WHITE, 13, Ui.MEDIUM);
            setClickable(true);
        }

        void setActive(boolean a) {
            active = a;
            invalidate();
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float rad = Math.min(w, h) * 0.3f;
            r.set(0, 0, w, h);
            bg.setColor(active ? Ui.WHITE : Ui.TILE);
            c.drawRoundRect(r, rad, rad, bg);
            if (isPressed()) {
                bg.setColor(0x26000000);
                c.drawRoundRect(r, rad, rad, bg);
            }
            int s = Math.round(Math.min(w, h) * 0.42f);
            icon.setTint(active ? activeColor : Ui.WHITE);
            if (lines == null) {
                int l = Math.round((w - s) / 2f), t = Math.round((h - s) / 2f);
                icon.setBounds(l, t, l + s, t + s);
                icon.draw(c);
            } else {
                int l = Math.round(h * 0.3f), t = Math.round((h - s) / 2f);
                icon.setBounds(l, t, l + s, t + s);
                icon.draw(c);
                txt.setColor(active ? 0xFF1C1C1E : Ui.WHITE);
                float lh = txt.getTextSize() * 1.2f;
                float y = h / 2f - (lines.length - 1) * lh / 2f - (txt.ascent() + txt.descent()) / 2f;
                for (String line : lines) {
                    c.drawText(line, l + s + Ui.dp(12), y, txt);
                    y += lh;
                }
            }
        }
    }

    static final class VSlider extends View {
        interface OnChange {
            void onChange(float v);
        }

        OnChange listener;
        Runnable onTouchStart, onTouchEnd;
        private float value = 0.5f, startY, startV;
        private final Drawable icon;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        VSlider(Context c, int res) {
            super(c);
            icon = c.getDrawable(res).mutate();
            track.setColor(Ui.TILE);
            fill.setColor(Ui.WHITE);
        }

        void setValue(float v) {
            value = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float rad = w * 0.3f;
            r.set(0, 0, w, h);
            c.drawRoundRect(r, rad, rad, track);
            float top = h * (1 - value);
            c.save();
            c.clipRect(0, top, w, h);
            c.drawRoundRect(r, rad, rad, fill);
            c.restore();
            int s = Math.round(w * 0.38f);
            int l = Math.round((w - s) / 2f), t = Math.round(h - s - w * 0.28f);
            icon.setTint(top < t + s ? 0xFF8E8E93 : Ui.WHITE);
            icon.setBounds(l, t, l + s, t + s);
            icon.draw(c);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    startY = e.getY();
                    startV = value;
                    if (onTouchStart != null) onTouchStart.run();
                    animate().scaleX(1.06f).scaleY(1.03f).setDuration(120).start();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    value = Math.max(0f, Math.min(1f, startV + (startY - e.getY()) / getHeight()));
                    invalidate();
                    if (listener != null) listener.onChange(value);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && onTouchEnd != null) onTouchEnd.run();
                    return true;
                default:
                    return true;
            }
        }
    }
}
