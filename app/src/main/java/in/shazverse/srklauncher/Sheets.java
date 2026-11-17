package in.shazverse.srklauncher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

/** Bottom sheets: widget gallery and wallpaper chooser. */
final class Sheets {
    private Sheets() {}

    /** A dimmed overlay with a rounded panel that springs up from the bottom. */
    static class Sheet extends FrameLayout implements Motion.OverlayAnim {
        final LauncherActivity act;
        final LinearLayout panel;
        final LinearLayout body;

        Sheet(LauncherActivity a, String title, float heightFraction) {
            super(a);
            act = a;
            setBackgroundColor(0x66000000);
            setClickable(true);
            setOnClickListener(v -> a.closeOverlay());

            panel = new LinearLayout(a);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setClickable(true);
            final float r = Ui.dp(28);
            panel.setBackground(Ui.round(0xF21C1C1E, 28));
            panel.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight() + (int) r, r);
                }
            });
            panel.setClipToOutline(true);

            View grabber = new View(a);
            grabber.setBackground(Ui.round(0x66FFFFFF, 3));
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(Ui.dp(38), Ui.dp(5));
            gl.gravity = Gravity.CENTER_HORIZONTAL;
            gl.topMargin = Ui.dp(8);
            panel.addView(grabber, gl);

            LinearLayout head = new LinearLayout(a);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(Ui.dp(20), Ui.dp(10), Ui.dp(16), Ui.dp(6));
            TextView t = Ui.text(a, title, 24, Ui.WHITE, Ui.BOLD);
            head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView done = Ui.text(a, "Done", 17, Ui.BLUE, Ui.SEMIBOLD);
            done.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(4), Ui.dp(8));
            done.setOnClickListener(v -> a.closeOverlay());
            head.addView(done);
            panel.addView(head);

            ScrollView sv = new ScrollView(a);
            sv.setVerticalScrollBarEnabled(false);
            body = new LinearLayout(a);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(20), a.insetBottom + Ui.dp(24));
            sv.addView(body);
            panel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            addView(panel, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (a.lastH * heightFraction), Gravity.BOTTOM));
        }

        TextView section(String s) {
            TextView t = Ui.text(getContext(), s.toUpperCase(), 13, 0x99FFFFFF, Ui.SEMIBOLD);
            t.setPadding(Ui.dp(2), Ui.dp(18), 0, Ui.dp(10));
            body.addView(t);
            return t;
        }

        @Override
        public void enter() {
            setAlpha(0f);
            animate().alpha(1f).setDuration(200).start();
            panel.setTranslationY(act.lastH);
            panel.animate().translationY(0).setInterpolator(Motion.SOFT_SPRING).setDuration(560).start();
        }

        @Override
        public void exit(Runnable done) {
            panel.animate().translationY(panel.getHeight()).setInterpolator(Motion.EASE_IN).setDuration(240).start();
            animate().alpha(0f).setDuration(260).withEndAction(done).start();
        }
    }

    private static TextView chip(LauncherActivity a, String label, View.OnClickListener l) {
        TextView c = Ui.text(a, label, 14, Ui.WHITE, Ui.SEMIBOLD);
        c.setBackground(Ui.round(0x33FFFFFF, 14));
        c.setPadding(Ui.dp(14), Ui.dp(7), Ui.dp(14), Ui.dp(7));
        c.setOnClickListener(l);
        return c;
    }

    // ---------------------------------------------------------------- widgets

    static final class WidgetPicker extends Sheet {
        WidgetPicker(final LauncherActivity a) {
            super(a, "Widgets", 0.82f);
            section("SRK Widgets");
            addBuiltin("weather", "Weather", "Temperature and conditions where you are", new int[][]{{2, 2}, {4, 2}});
            addBuiltin("calendar", "Calendar", "Today's date and your next event", new int[][]{{2, 2}, {4, 2}});
            addBuiltin("battery", "Batteries", "Your phone's battery level", new int[][]{{2, 2}, {4, 2}});
            addBuiltin("clock", "Clock", "An analog clock", new int[][]{{2, 2}});

            section("Your Apps");
            final PackageManager pm = a.getPackageManager();
            List<AppWidgetProviderInfo> list = new ArrayList<>();
            try {
                list.addAll(AppWidgetManager.getInstance(a).getInstalledProviders());
            } catch (Exception ignored) {
            }
            final Collator col = Collator.getInstance();
            final java.util.HashMap<AppWidgetProviderInfo, String> appNames = new java.util.HashMap<>();
            for (AppWidgetProviderInfo info : list) {
                String app;
                try {
                    app = pm.getApplicationLabel(pm.getApplicationInfo(info.provider.getPackageName(), 0)).toString();
                } catch (Exception e) {
                    app = info.provider.getPackageName();
                }
                appNames.put(info, app);
            }
            list.sort((x, y) -> col.compare(appNames.get(x), appNames.get(y)));
            if (list.isEmpty()) {
                body.addView(Ui.text(a, "No app widgets found", 15, 0x99FFFFFF, Ui.REGULAR));
            }
            for (final AppWidgetProviderInfo info : list) {
                LinearLayout row = new LinearLayout(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
                ImageView img = new ImageView(a);
                img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                Drawable d = null;
                try {
                    d = info.loadPreviewImage(a, a.getResources().getDisplayMetrics().densityDpi);
                    if (d == null) d = info.loadIcon(a, a.getResources().getDisplayMetrics().densityDpi);
                } catch (Exception ignored) {
                }
                if (d != null) img.setImageDrawable(d);
                img.setBackground(Ui.round(0x1AFFFFFF, 14));
                img.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));
                row.addView(img, new LinearLayout.LayoutParams(Ui.dp(96), Ui.dp(72)));
                LinearLayout txt = new LinearLayout(a);
                txt.setOrientation(LinearLayout.VERTICAL);
                txt.setPadding(Ui.dp(14), 0, 0, 0);
                String label;
                try {
                    label = info.loadLabel(pm);
                } catch (Exception e) {
                    label = "Widget";
                }
                TextView n = Ui.text(a, label, 16, Ui.WHITE, Ui.SEMIBOLD);
                n.setMaxLines(2);
                txt.addView(n);
                txt.addView(Ui.text(a, appNames.get(info), 13, 0x99FFFFFF, Ui.REGULAR));
                row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(chip(a, "Add", v -> {
                    a.closeOverlayNow();
                    a.addSystemWidget(info);
                }));
                body.addView(row);
            }
        }

        private void addBuiltin(final String kind, String name, String desc, int[][] sizes) {
            LauncherActivity a = act;
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(6), 0, Ui.dp(10));
            View preview = LauncherActivity.makeBuiltinWidget(a, kind);
            preview.setClickable(false);
            preview.setEnabled(false);
            row.addView(preview, new LinearLayout.LayoutParams(Ui.dp(104), Ui.dp(104)));
            LinearLayout txt = new LinearLayout(a);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setPadding(Ui.dp(16), 0, 0, 0);
            txt.addView(Ui.text(a, name, 17, Ui.WHITE, Ui.SEMIBOLD));
            TextView d = Ui.text(a, desc, 13, 0x99FFFFFF, Ui.REGULAR);
            d.setPadding(0, Ui.dp(2), 0, Ui.dp(10));
            txt.addView(d);
            LinearLayout chips = new LinearLayout(a);
            for (final int[] s : sizes) {
                String lbl = s[0] == 2 ? "Small" : (s[1] >= 4 ? "Large" : "Medium");
                TextView c = chip(a, "+ " + lbl, v -> {
                    a.closeOverlayNow();
                    a.addBuiltinWidget(kind, s[0], s[1]);
                });
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cl.rightMargin = Ui.dp(8);
                chips.addView(c, cl);
            }
            txt.addView(chips);
            row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            body.addView(row);
        }
    }

    // -------------------------------------------------------------- wallpaper

    static final class WallpaperPicker extends Sheet {
        WallpaperPicker(final LauncherActivity a) {
            super(a, "Wallpaper", 0.62f);
            section("SRK Wallpapers");
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout strip = new LinearLayout(a);
            int tw = Ui.dp(96), th = Math.round(tw * Glass.screenH / (float) Math.max(1, Glass.screenW));
            if (th <= 0 || th > Ui.dp(260)) th = Ui.dp(200);
            String cur = Glass.mode(a.store.prefs);
            for (int i = 0; i < Glass.PRESET_NAMES.length; i++) {
                final int idx = i;
                LinearLayout cell = new LinearLayout(a);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView img = new ImageView(a);
                Bitmap b = Glass.preset(i, tw, th);
                img.setImageBitmap(b);
                final float rr = Ui.dp(16);
                img.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rr);
                    }
                });
                img.setClipToOutline(true);
                if (cur.equals("preset:" + i)) {
                    img.setPadding(Ui.dp(3), Ui.dp(3), Ui.dp(3), Ui.dp(3));
                    img.setBackground(Ui.round(Ui.BLUE, 18));
                }
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setOnClickListener(v -> {
                    a.closeOverlay();
                    a.setWallpaperMode("preset:" + idx);
                });
                cell.addView(img, new LinearLayout.LayoutParams(tw, th));
                TextView n = Ui.text(a, Glass.PRESET_NAMES[i], 13, 0xCCFFFFFF, Ui.MEDIUM);
                n.setPadding(0, Ui.dp(6), 0, 0);
                cell.addView(n);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = Ui.dp(12);
                strip.addView(cell, lp);
            }
            hs.addView(strip);
            body.addView(hs);

            section("More");
            body.addView(button(a, "Choose a Photo…", v -> {
                a.closeOverlayNow();
                a.pickWallpaperPhoto();
            }));
            body.addView(button(a, "Use Phone Wallpaper" + (cur.equals("system") ? "  ✓" : ""), v -> {
                a.closeOverlay();
                a.setWallpaperMode("system");
            }));
        }

        private static TextView button(LauncherActivity a, String s, View.OnClickListener l) {
            TextView t = Ui.text(a, s, 17, Ui.WHITE, Ui.MEDIUM);
            t.setBackground(Ui.round(0x26FFFFFF, 14));
            t.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
            t.setOnClickListener(l);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(10);
            t.setLayoutParams(lp);
            return t;
        }
    }
}
