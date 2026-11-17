package in.shazverse.srklauncher;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.graphics.Insets;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SRK Launcher — an iOS-style home screen for Android. */
public class LauncherActivity extends Activity
        implements AppIconView.Callbacks, PagerView.Listener, WidgetFrame.Host {

    static final int REQ_LOCATION = 11, REQ_CALENDAR = 12, REQ_BIND = 21, REQ_CONFIG = 22, REQ_PHOTO = 23;

    AppStore store;
    int insetTop, insetBottom, lastW, lastH;
    int iconSize = 0;
    boolean torchOn;

    private static final int COLS = 4;
    private int rows = 6;
    private FrameLayout root, homeLayer, overlayLayer, dockFrame, pill;
    private Glass.WallpaperView wallpaperView;
    private PagerView pager;
    private LinearLayout dots, dockRow, editBar;
    private TextView pillLabel;
    private AppLibraryPage library;

    private final ArrayList<AppIconView> icons = new ArrayList<>();
    private final ArrayList<WidgetFrame> frames = new ArrayList<>();
    private final ArrayList<View> homeItems = new ArrayList<>();
    private final ArrayList<View> glassViews = new ArrayList<>();
    final ArrayList<Widgets.CalendarCard> calendars = new ArrayList<>();
    private List<List<Placed>> pages = new ArrayList<>();
    private int[] pageOfIndex = new int[0];

    private boolean editing, loaded, pagerReady, launchedApp, rebuildPosted;
    private View overlay;
    private View dragging;
    private int edgeDir;
    private float blurLevel;
    private ValueAnimator blurAnim;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LauncherApps launcherApps;
    private LauncherApps.Callback laCallback;
    private CameraManager cameraManager;
    private String torchId;
    private CameraManager.TorchCallback torchCb;
    private AppWidgetHost widgetHost;
    private AppWidgetManager awm;
    private int[] pendingWidget; // id, w, h

    static final class Placed {
        final int index, col, row, w, h;

        Placed(int index, int col, int row, int w, int h) {
            this.index = index;
            this.col = col;
            this.row = row;
            this.w = w;
            this.h = h;
        }
    }

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        store = new AppStore(this);
        Weather.init(this);

        Window w = getWindow();
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        w.setStatusBarColor(0);
        w.setNavigationBarColor(0);
        if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);

        root = new FrameLayout(this);
        homeLayer = new FrameLayout(this);
        overlayLayer = new FrameLayout(this);
        root.addView(homeLayer, match());
        root.addView(overlayLayer, match());

        wallpaperView = new Glass.WallpaperView(this);
        homeLayer.addView(wallpaperView, match());

        pager = new PagerView(this);
        pager.setListener(this);
        homeLayer.addView(pager, match());

        // iOS 16+ "Search" pill above the Dock, turns into page dots while swiping.
        pill = new FrameLayout(this);
        pill.setBackground(new Glass.GlassDrawable(pill, Ui.dp(15), 0x38FFFFFF, 0x4DFFFFFF));
        pill.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        pillLabel = Ui.text(this, "Search", 13, Ui.WHITE, Ui.SEMIBOLD);
        Drawable sd = Ui.icon(this, R.drawable.ic_search, Ui.WHITE);
        sd.setBounds(0, 0, Ui.dp(14), Ui.dp(14));
        pillLabel.setCompoundDrawablesRelative(sd, null, null, null);
        pillLabel.setCompoundDrawablePadding(Ui.dp(4));
        pillLabel.setGravity(Gravity.CENTER);
        pill.addView(pillLabel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        dots.setAlpha(0f);
        pill.addView(dots, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        pill.setOnClickListener(v -> showSpotlight());
        homeLayer.addView(pill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(30), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        dockFrame = new FrameLayout(this);
        dockFrame.setBackground(new Glass.GlassDrawable(dockFrame, Ui.dp(34), 0x38FFFFFF, 0x4DFFFFFF));
        dockRow = new LinearLayout(this);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setPadding(Ui.dp(6), 0, Ui.dp(6), 0);
        dockFrame.addView(dockRow, match());
        homeLayer.addView(dockFrame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(90), Gravity.BOTTOM));

        editBar = buildEditBar();
        editBar.setVisibility(View.GONE);
        homeLayer.addView(editBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        homeLayer.setOnDragListener(this::onDrag);

        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int t, b;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                t = i.top;
                b = i.bottom;
            } else {
                t = insets.getSystemWindowInsetTop();
                b = insets.getSystemWindowInsetBottom();
            }
            if (t != insetTop || b != insetBottom) {
                insetTop = t;
                insetBottom = b;
                relayout();
            }
            return insets;
        });
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int W = r - l, H = b - t;
            if (W != lastW || H != lastH) {
                lastW = W;
                lastH = H;
                relayout();
            }
        });

        awm = AppWidgetManager.getInstance(this);
        widgetHost = new AppWidgetHost(this, 0x5E4B);

        launcherApps = getSystemService(LauncherApps.class);
        laCallback = new LauncherApps.Callback() {
            @Override
            public void onPackageRemoved(String p, UserHandle u) {
                reload();
            }

            @Override
            public void onPackageAdded(String p, UserHandle u) {
                reload();
            }

            @Override
            public void onPackageChanged(String p, UserHandle u) {
                reload();
            }

            @Override
            public void onPackagesAvailable(String[] p, UserHandle u, boolean replacing) {
                reload();
            }

            @Override
            public void onPackagesUnavailable(String[] p, UserHandle u, boolean replacing) {
                reload();
            }
        };
        try {
            launcherApps.registerCallback(laCallback, main);
        } catch (Exception ignored) {
        }
        setupTorch();
        loadWallpaper();
        reload();
        main.postDelayed(this::maybeShowWelcome, 1200);
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            widgetHost.startListening();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Weather.refresh(this, false);
        for (Widgets.CalendarCard c : calendars) c.load();
        if (overlay instanceof ControlCenter) ((ControlCenter) overlay).refresh();
        if (launchedApp) {
            launchedApp = false;
            // iOS "zoom back" to the Home Screen
            homeLayer.setScaleX(1.08f);
            homeLayer.setScaleY(1.08f);
            homeLayer.setAlpha(0.3f);
            homeLayer.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setInterpolator(Motion.SPRING).setDuration(560).start();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            widgetHost.stopListening();
        } catch (Exception ignored) {
        }
        closeOverlayNow();
        if (editing) setEditing(false);
        if (library != null) library.clearSearchIfActive();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            launcherApps.unregisterCallback(laCallback);
        } catch (Exception ignored) {
        }
        try {
            if (cameraManager != null && torchCb != null) cameraManager.unregisterTorchCallback(torchCb);
        } catch (Exception ignored) {
        }
        bg.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        boolean alreadyOnHome = hasWindowFocus()
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) != Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT;
        if (overlay != null) {
            closeOverlay();
            return;
        }
        if (editing) {
            setEditing(false);
            return;
        }
        if (alreadyOnHome) {
            if (library != null) library.clearSearchIfActive();
            if (pager.getCurrent() != 1) pager.setPage(1, true);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (overlay != null) closeOverlay();
        else if (editing) setEditing(false);
        else if (library != null && pager.getCurrent() == pager.getChildCount() - 1) {
            library.clearSearchIfActive();
            pager.setPage(1, true);
        } else if (pager.getCurrent() != 1) pager.setPage(1, true);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_LOCATION) Weather.refresh(this, true);
        if (code == REQ_CALENDAR) for (Widgets.CalendarCard c : calendars) c.load();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_BIND) {
            if (pendingWidget == null) return;
            if (result == RESULT_OK) {
                configureOrAdd(pendingWidget[0], awm.getAppWidgetInfo(pendingWidget[0]));
            } else {
                widgetHost.deleteAppWidgetId(pendingWidget[0]);
                pendingWidget = null;
            }
        } else if (req == REQ_CONFIG) {
            if (pendingWidget == null) return;
            if (result == RESULT_OK) finishAddWidget();
            else {
                widgetHost.deleteAppWidgetId(pendingWidget[0]);
                pendingWidget = null;
            }
        } else if (req == REQ_PHOTO && result == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            bg.execute(() -> {
                boolean ok = false;
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(Glass.photoFile(this))) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
                    ok = in != null;
                } catch (Exception ignored) {
                }
                final boolean done = ok;
                main.post(() -> {
                    if (done) setWallpaperMode("photo");
                    else toast("Couldn't open that photo");
                });
            });
        }
    }

    // ================================================================== loading & layout

    private void reload() {
        final int px = Ui.dp(72);
        bg.execute(() -> {
            final Map<String, AppInfo> m = AppStore.loadApps(this, px);
            main.post(() -> {
                if (isDestroyed()) return;
                store.apply(m);
                loaded = true;
                rebuild();
            });
        });
    }

    private int[] screenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect r = getWindowManager().getCurrentWindowMetrics().getBounds();
            return new int[]{r.width(), r.height()};
        }
        DisplayMetrics m = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        return new int[]{m.widthPixels, m.heightPixels};
    }

    private void loadWallpaper() {
        final int[] s = screenSize();
        bg.execute(() -> {
            Glass.load(this, store.prefs, s[0], s[1]);
            main.post(this::applyWallpaper);
        });
    }

    private void applyWallpaper() {
        wallpaperView.setVisibility(Glass.wallpaper != null ? View.VISIBLE : View.GONE);
        wallpaperView.invalidate();
        dockFrame.invalidate();
        pill.invalidate();
        invalidateGlass();
    }

    void setWallpaperMode(String mode) {
        store.prefs.edit().putString("wp", mode).apply();
        loadWallpaper();
        if ("system".equals(mode)) {
            toast("Using your phone's wallpaper");
        }
    }

    void pickWallpaperPhoto() {
        Intent i = Build.VERSION.SDK_INT >= 33
                ? new Intent(MediaStore.ACTION_PICK_IMAGES)
                : new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
        try {
            startActivityForResult(i, REQ_PHOTO);
        } catch (Exception e) {
            toast("No photo picker found");
        }
    }

    void registerGlass(View v) {
        glassViews.add(v);
    }

    private void invalidateGlass() {
        for (View v : glassViews) v.invalidate();
    }

    private void relayout() {
        if (lastW == 0 || lastH == 0) return;
        int side = Ui.dp(14);
        int cellW = (lastW - side * 2) / COLS;
        iconSize = Math.min(Ui.dp(64), Math.round(cellW * 0.68f));
        int dockH = iconSize + Ui.dp(28);
        FrameLayout.LayoutParams dl = (FrameLayout.LayoutParams) dockFrame.getLayoutParams();
        dl.height = dockH;
        dl.leftMargin = dl.rightMargin = Ui.dp(10);
        dl.bottomMargin = insetBottom + Ui.dp(8);
        dockFrame.setLayoutParams(dl);
        FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) pill.getLayoutParams();
        pl.bottomMargin = dl.bottomMargin + dockH + Ui.dp(12);
        pill.setLayoutParams(pl);
        editBar.setPadding(Ui.dp(16), insetTop + Ui.dp(4), Ui.dp(16), Ui.dp(4));
        int gridH = lastH - insetTop - Ui.dp(16) - bottomReserve();
        int cellH = iconSize + Ui.dp(34);
        rows = Math.max(4, Math.min(7, gridH / Math.max(1, cellH)));
        if (loaded) rebuild();
    }

    private int bottomReserve() {
        FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) pill.getLayoutParams();
        return pl.bottomMargin + Ui.dp(38);
    }

    void requestRebuild() {
        if (rebuildPosted) return;
        rebuildPosted = true;
        main.post(() -> {
            rebuildPosted = false;
            rebuild();
        });
    }

    private int[] spanOf(String e) {
        if (AppStore.isWidget(e)) {
            AppStore.WidgetSpec s = AppStore.WidgetSpec.parse(e);
            if (s != null) return new int[]{Math.min(COLS, Math.max(1, s.w)), Math.min(rows, Math.max(1, s.h))};
        }
        return new int[]{1, 1};
    }

    /** Places Home Screen entries onto pages, first-fit in reading order (like iOS). */
    private List<List<Placed>> pack() {
        List<List<Placed>> out = new ArrayList<>();
        int n = store.home.size();
        pageOfIndex = new int[n];
        boolean[][] occ = new boolean[rows][COLS];
        List<Placed> cur = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int[] sp = spanOf(store.home.get(i));
            int[] pos = findSpot(occ, sp[0], sp[1]);
            if (pos == null) {
                out.add(cur);
                cur = new ArrayList<>();
                occ = new boolean[rows][COLS];
                pos = findSpot(occ, sp[0], sp[1]);
                if (pos == null) continue;
            }
            for (int r = pos[1]; r < pos[1] + sp[1]; r++)
                for (int c = pos[0]; c < pos[0] + sp[0]; c++) occ[r][c] = true;
            cur.add(new Placed(i, pos[0], pos[1], sp[0], sp[1]));
            pageOfIndex[i] = out.size();
        }
        out.add(cur);
        return out;
    }

    private int[] findSpot(boolean[][] occ, int w, int h) {
        for (int r = 0; r + h <= rows; r++) {
            for (int c = 0; c + w <= COLS; c++) {
                boolean free = true;
                for (int rr = r; rr < r + h && free; rr++)
                    for (int cc = c; cc < c + w; cc++)
                        if (occ[rr][cc]) {
                            free = false;
                            break;
                        }
                if (free) return new int[]{c, r};
            }
        }
        return null;
    }

    private void rebuild() {
        if (lastW == 0 || iconSize == 0 || !loaded) return;
        int keep = pager.getCurrent();
        boolean wasLibrary = pager.getChildCount() > 0 && keep == pager.getChildCount() - 1;
        icons.clear();
        frames.clear();
        homeItems.clear();
        glassViews.clear();
        calendars.clear();
        pager.removeAllViews();

        pager.addView(buildTodayPage());
        pages = pack();
        if (editing) pages.add(new ArrayList<>());
        for (List<Placed> page : pages) pager.addView(buildHomePage(page));
        library = new AppLibraryPage(this);
        pager.addView(library);
        buildDock();
        buildDots();

        int target;
        if (!pagerReady) target = 1;
        else if (wasLibrary) target = pager.getChildCount() - 1;
        else target = Math.min(keep, pager.getChildCount() - 1);
        pagerReady = true;
        pager.requestLayout();
        pager.setPage(target, false);
        onScrollPosition(target);
    }

    private View buildHomePage(List<Placed> page) {
        IconGrid g = new IconGrid(this, COLS, rows);
        g.setPadding(Ui.dp(14), insetTop + Ui.dp(16), Ui.dp(14), bottomReserve());
        for (Placed pl : page) {
            String e = store.home.get(pl.index);
            if (AppStore.isWidget(e)) {
                WidgetFrame f = makeWidgetFrame(e);
                f.index = pl.index;
                f.setEditing(editing);
                frames.add(f);
                homeItems.add(f);
                glassViews.add(f);
                g.add(f, pl.col, pl.row, pl.w, pl.h, Ui.dp(8));
            } else if (AppStore.isFolder(e)) {
                String id = e.substring(7);
                AppStore.HomeFolder hf = store.folders.get(id);
                if (hf == null) continue;
                AppIconView v = new AppIconView(this, id, hf.name, store.folderApps(id), iconSize, this);
                v.place = AppIconView.HOME;
                v.index = pl.index;
                v.setEditing(editing);
                icons.add(v);
                homeItems.add(v);
                glassViews.add(v);
                g.add(v, pl.col, pl.row, 1, 1, 0);
            } else {
                AppInfo a = store.apps.get(e);
                if (a == null) continue;
                AppIconView v = newIcon(a, true, AppIconView.HOME, pl.index);
                homeItems.add(v);
                g.add(v, pl.col, pl.row, 1, 1, 0);
            }
        }
        return g;
    }

    private WidgetFrame makeWidgetFrame(String entry) {
        AppStore.WidgetSpec s = AppStore.WidgetSpec.parse(entry);
        View content;
        if (s != null && "sys".equals(s.kind)) content = createSystemWidgetView(s.id);
        else content = makeBuiltinWidget(this, s == null ? "" : s.kind);
        return new WidgetFrame(this, entry, content, this);
    }

    static View makeBuiltinWidget(LauncherActivity a, String kind) {
        switch (kind) {
            case "weather":
                return new Widgets.WeatherCard(a);
            case "clock":
                return new Widgets.ClockCard(a);
            case "calendar": {
                Widgets.CalendarCard c = new Widgets.CalendarCard(a);
                a.calendars.add(c);
                return c;
            }
            case "battery":
                return new Widgets.BatteryCard(a);
            default: {
                TextView t = Ui.text(a, "Widget", 14, Ui.WHITE, Ui.MEDIUM);
                t.setGravity(Gravity.CENTER);
                return t;
            }
        }
    }

    private View createSystemWidgetView(int id) {
        try {
            AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
            if (info != null) {
                AppWidgetHostView hv = widgetHost.createView(this, id, info);
                hv.setPadding(0, 0, 0, 0);
                return hv;
            }
        } catch (Exception ignored) {
        }
        TextView t = Ui.text(this, "Widget unavailable", 13, 0xB3FFFFFF, Ui.MEDIUM);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private View buildTodayPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(18);
        col.setPadding(pad, insetTop + Ui.dp(16), pad, insetBottom + Ui.dp(28));
        sv.addView(col);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        date.setTypeface(Ui.SEMIBOLD);
        date.setTextColor(0xE6FFFFFF);
        date.setGravity(Gravity.CENTER);
        date.setShadowLayer(Ui.dp(3), 0, Ui.dp(1), 0x40000000);
        col.addView(date, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 88);
        clock.setTypeface(Ui.BOLD);
        clock.setLetterSpacing(-0.03f);
        clock.setIncludeFontPadding(false);
        clock.setTextColor(0xF2FFFFFF);
        clock.setGravity(Gravity.CENTER);
        clock.setShadowLayer(Ui.dp(4), 0, Ui.dp(1), 0x40000000);
        col.addView(clock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView search = Ui.text(this, "Search", 17, 0xB3FFFFFF, Ui.REGULAR);
        search.setBackground(new Glass.GlassDrawable(search, Ui.dp(12), 0x2EFFFFFF, Ui.FILL));
        glassViews.add(search);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
        Drawable sd = Ui.icon(this, R.drawable.ic_search, 0xB3FFFFFF);
        sd.setBounds(0, 0, Ui.dp(20), Ui.dp(20));
        search.setCompoundDrawablesRelative(sd, null, null, null);
        search.setCompoundDrawablePadding(Ui.dp(6));
        search.setOnClickListener(v -> showSpotlight());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(44));
        slp.topMargin = Ui.dp(16);
        slp.bottomMargin = Ui.dp(18);
        col.addView(search, slp);

        int width = lastW - pad * 2;
        int gap = Ui.dp(16);
        int small = (width - gap) / 2;

        col.addView(new Widgets.WeatherCard(this), lp(ViewGroup.LayoutParams.MATCH_PARENT, small, gap));

        LinearLayout row = new LinearLayout(this);
        row.addView(new Widgets.ClockCard(this), new LinearLayout.LayoutParams(small, small));
        Widgets.CalendarCard cal = new Widgets.CalendarCard(this);
        calendars.add(cal);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(small, small);
        cl.leftMargin = gap;
        row.addView(cal, cl);
        col.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, small, gap));

        col.addView(new Widgets.BatteryCard(this), lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(small * 0.75f), gap));

        LinearLayout sug = new LinearLayout(this);
        sug.setOrientation(LinearLayout.VERTICAL);
        sug.setBackground(new Glass.GlassDrawable(sug, Ui.dp(22), 0xB31C1C1E, Ui.CARD));
        glassViews.add(sug);
        sug.setPadding(Ui.dp(8), Ui.dp(12), Ui.dp(8), Ui.dp(8));
        TextView st = Ui.text(this, "Suggestions", 14, 0xCCFFFFFF, Ui.SEMIBOLD);
        st.setPadding(Ui.dp(8), 0, 0, Ui.dp(6));
        sug.addView(st);
        LinearLayout icRow = new LinearLayout(this);
        for (AppInfo a : store.suggestions(4)) {
            AppIconView iv = newIcon(a, true, AppIconView.OTHER, -1);
            icRow.addView(iv, new LinearLayout.LayoutParams(0, iconSize + Ui.dp(32), 1));
        }
        sug.addView(icRow);
        col.addView(sug, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, gap));

        TextView edit = Ui.text(this, "Edit", 15, Ui.WHITE, Ui.SEMIBOLD);
        edit.setBackground(Ui.round(0x33FFFFFF, 16));
        edit.setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(18), Ui.dp(8));
        edit.setOnClickListener(v -> showOverlay(new Sheets.WidgetPicker(this)));
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        el.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(edit, el);
        return sv;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int bottom) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, h);
        l.bottomMargin = bottom;
        return l;
    }

    private void buildDock() {
        dockRow.removeAllViews();
        for (int i = 0; i < store.dock.size(); i++) {
            AppInfo a = store.apps.get(store.dock.get(i));
            if (a == null) continue;
            AppIconView v = newIcon(a, false, AppIconView.DOCK, i);
            dockRow.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }

    private void buildDots() {
        dots.removeAllViews();
        int n = pages.size();
        for (int i = 0; i < n; i++) {
            View d = new View(this);
            d.setBackground(Ui.round(Ui.WHITE, 4));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(Ui.dp(7), Ui.dp(7));
            l.leftMargin = l.rightMargin = Ui.dp(4);
            dots.addView(d, l);
        }
    }

    private AppIconView newIcon(AppInfo a, boolean label, String place, int idx) {
        AppIconView v = new AppIconView(this, a, iconSize, label, this);
        v.place = place;
        v.index = idx;
        v.setEditing(editing);
        icons.add(v);
        return v;
    }

    // ================================================================== pager callbacks

    private final Runnable showSearchLabel = () -> {
        pillLabel.animate().alpha(1f).setDuration(200).start();
        dots.animate().alpha(0f).setDuration(200).start();
    };

    @Override
    public void onPageChanged(int page) {
        if (library != null && page != pager.getChildCount() - 1) library.clearSearchIfActive();
    }

    @Override
    public void onScrollPosition(float pos) {
        int last = pages.size();
        float a;
        if (pos < 1) a = Math.max(0f, pos);
        else if (pos > last) a = Math.max(0f, 1f - (pos - last));
        else a = 1f;
        dockFrame.setAlpha(a);
        pill.setAlpha(a);
        dockFrame.setTranslationY((1 - a) * Ui.dp(40));
        dockFrame.setVisibility(a <= 0.01f ? View.INVISIBLE : View.VISIBLE);
        pill.setVisibility(a <= 0.01f ? View.INVISIBLE : View.VISIBLE);
        for (int i = 0; i < dots.getChildCount(); i++) {
            float d = Math.abs(pos - (i + 1));
            dots.getChildAt(i).setAlpha(d < 0.5f ? 1f : 0.4f);
        }
        boolean moving = Math.abs(pos - Math.round(pos)) > 0.001f;
        if (moving && pages.size() > 1) {
            main.removeCallbacks(showSearchLabel);
            pillLabel.setAlpha(0f);
            dots.setAlpha(1f);
            main.postDelayed(showSearchLabel, 1100);
        }
        if (Glass.blurred != null) invalidateGlass();
    }

    @Override
    public boolean canSwipeDown(int page) {
        return !editing && overlay == null && page >= 1 && page <= pages.size();
    }

    @Override
    public void onSwipeDown(float x, float y) {
        int topZone = insetTop + Ui.dp(64);
        if (y < topZone) {
            if (x > lastW * 0.55f) showControlCenter();
            else showOverlay(new NotificationCentre(this));
        } else {
            showSpotlight();
        }
    }

    @Override
    public void onEmptyLongPress() {
        if (!editing && overlay == null) {
            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            setEditing(true);
        }
    }

    // ================================================================== icon callbacks

    @Override
    public void onIconClick(AppIconView v) {
        if (v.isFolder()) showOverlay(new FolderOverlay(this, v.folderId, v));
        else launch(v.app, v);
    }

    @Override
    public boolean onIconLongClick(AppIconView v) {
        if (v.isFolder()) showFolderMenu(v);
        else showAppMenu(v.app, v, v.ownerFolder);
        return true;
    }

    @Override
    public void onIconRemove(AppIconView v) {
        if (v.isFolder()) {
            final String id = v.folderId;
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Remove “" + v.label + "”?")
                    .setMessage("The apps inside will move to your Home Screen.")
                    .setPositiveButton("Remove Folder", (d, w) -> {
                        store.dissolveFolder(id);
                        rebuild();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        final AppInfo a = v.app;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Remove “" + a.label + "”?")
                .setMessage("Removing from Home Screen will keep the app in your App Library.")
                .setPositiveButton("Remove from Home Screen", (d, w) -> {
                    store.removeEntry(a.key);
                    rebuild();
                })
                .setNegativeButton("Delete App", (d, w) -> uninstall(a.pkg))
                .setNeutralButton("Cancel", null)
                .show();
    }

    @Override
    public void onIconStartDrag(AppIconView v) {
        if (!v.removable()) return;
        startDrag(v, v.entry());
    }

    private void startDrag(View v, String entry) {
        dragging = v;
        ClipData data = ClipData.newPlainText("entry", entry);
        v.startDragAndDrop(data, new View.DragShadowBuilder(v), v, 0);
        v.setAlpha(0.25f);
    }

    private void showFolderMenu(final AppIconView v) {
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        PopupMenu pm = new PopupMenu(this, v);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Open & Rename");
        m.add(0, 2, 1, "Edit Home Screen");
        m.add(0, 3, 2, "Remove Folder");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    showOverlay(new FolderOverlay(this, v.folderId, v));
                    break;
                case 2:
                    setEditing(true);
                    break;
                case 3:
                    onIconRemove(v);
                    break;
                default:
                    break;
            }
            return true;
        });
        pm.show();
    }

    void showAppMenu(final AppInfo a, View anchor) {
        showAppMenu(a, anchor, null);
    }

    void showAppMenu(final AppInfo a, View anchor, final String ownerFolder) {
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        final List<ShortcutInfo> shortcuts = getShortcuts(a.pkg);
        for (int i = 0; i < shortcuts.size() && i < 4; i++) {
            CharSequence l = shortcuts.get(i).getShortLabel();
            m.add(1, 100 + i, i, l == null ? "Shortcut" : l);
        }
        boolean inDock = store.dock.contains(a.key), inHome = store.home.contains(a.key);
        if (ownerFolder != null) m.add(2, 8, 9, "Remove from Folder");
        m.add(2, 1, 10, "Edit Home Screen");
        if (inDock) m.add(2, 2, 11, "Remove from Dock");
        else if (store.dock.size() < AppStore.DOCK_MAX && ownerFolder == null) m.add(2, 3, 11, "Add to Dock");
        if (inHome) m.add(2, 4, 12, "Remove from Home Screen");
        else if (!inDock && ownerFolder == null && store.folderOf(a.key) == null) m.add(2, 5, 12, "Add to Home Screen");
        m.add(2, 6, 13, "App Info");
        m.add(2, 7, 14, "Delete App");
        pm.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id >= 100) {
                try {
                    launcherApps.startShortcut(shortcuts.get(id - 100), null, null);
                } catch (Exception e) {
                    toast("Couldn't open shortcut");
                }
                return true;
            }
            switch (id) {
                case 1:
                    closeOverlayNow();
                    setEditing(true);
                    break;
                case 2:
                    store.removeFromDock(a.key);
                    rebuild();
                    break;
                case 3:
                    store.addToDock(a.key);
                    rebuild();
                    break;
                case 4:
                    store.removeEntry(a.key);
                    rebuild();
                    break;
                case 5:
                    store.addToHome(a.key);
                    rebuild();
                    break;
                case 6:
                    openIntent(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
                    break;
                case 7:
                    uninstall(a.pkg);
                    break;
                case 8:
                    store.removeFromFolder(ownerFolder, a.key);
                    closeOverlayNow();
                    rebuild();
                    break;
                default:
                    break;
            }
            return true;
        });
        pm.show();
    }

    private List<ShortcutInfo> getShortcuts(String pkg) {
        try {
            if (!launcherApps.hasShortcutHostPermission()) return Collections.emptyList();
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(pkg);
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<ShortcutInfo> l = launcherApps.getShortcuts(q, Process.myUserHandle());
            return l == null ? Collections.<ShortcutInfo>emptyList() : l;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // ================================================================== widgets

    @Override
    public void onWidgetLongPress(final WidgetFrame f) {
        final AppStore.WidgetSpec s = f.spec;
        if (s == null) return;
        PopupMenu pm = new PopupMenu(this, f, Gravity.CENTER);
        Menu m = pm.getMenu();
        if (!"clock".equals(s.kind)) {
            if (!(s.w == 2 && s.h == 2)) m.add(0, 1, 0, "Small");
            if (!(s.w == 4 && s.h == 2)) m.add(0, 2, 1, "Medium");
            if ("sys".equals(s.kind) && !(s.w == 4 && s.h == 4)) m.add(0, 3, 2, "Large");
            if ("sys".equals(s.kind) && !(s.w == 4 && s.h == 1)) m.add(0, 4, 3, "Wide strip");
        }
        m.add(1, 5, 10, "Edit Home Screen");
        m.add(1, 6, 11, "Remove Widget");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    store.resizeWidget(f.entry, 2, 2);
                    rebuild();
                    break;
                case 2:
                    store.resizeWidget(f.entry, 4, 2);
                    rebuild();
                    break;
                case 3:
                    store.resizeWidget(f.entry, 4, 4);
                    rebuild();
                    break;
                case 4:
                    store.resizeWidget(f.entry, 4, 1);
                    rebuild();
                    break;
                case 5:
                    setEditing(true);
                    break;
                case 6:
                    onWidgetRemove(f);
                    break;
                default:
                    break;
            }
            return true;
        });
        pm.show();
    }

    @Override
    public void onWidgetRemove(final WidgetFrame f) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Remove Widget?")
                .setMessage("Removing this widget will not delete any apps or data.")
                .setPositiveButton("Remove", (d, w) -> {
                    if (f.spec != null && "sys".equals(f.spec.kind)) {
                        try {
                            widgetHost.deleteAppWidgetId(f.spec.id);
                        } catch (Exception ignored) {
                        }
                    }
                    store.removeEntry(f.entry);
                    rebuild();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onWidgetStartDrag(WidgetFrame f) {
        startDrag(f, f.entry);
    }

    /** Where a new widget goes: the top of the Home Screen page you are on. */
    private int insertIndexForCurrentPage() {
        int hp = pager.getCurrent() - 1;
        if (hp >= 0 && hp < pages.size()) {
            int min = Integer.MAX_VALUE;
            for (Placed p : pages.get(hp)) min = Math.min(min, p.index);
            if (min != Integer.MAX_VALUE) return min;
            return pageEnd(hp);
        }
        pager.setPage(1, true);
        return 0;
    }

    void addBuiltinWidget(String kind, int w, int h) {
        store.addWidget(kind, w, h, 0, insertIndexForCurrentPage());
        if (editing) setEditing(false);
        rebuild();
    }

    void addSystemWidget(AppWidgetProviderInfo info) {
        int cellW = Math.max(1, (lastW - Ui.dp(28)) / COLS);
        int cellH = iconSize + Ui.dp(34);
        int w = info.minWidth <= cellW * 2 ? 2 : 4;
        int hCells = (int) Math.ceil(info.minHeight / (float) cellH);
        int h;
        if (w == 2) h = 2;
        else h = hCells <= 1 ? 1 : (hCells <= 2 ? 2 : 4);
        try {
            int id = widgetHost.allocateAppWidgetId();
            pendingWidget = new int[]{id, w, h};
            boolean ok = awm.bindAppWidgetIdIfAllowed(id, info.getProfile(), info.provider, null);
            if (ok) {
                configureOrAdd(id, info);
            } else {
                Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
                startActivityForResult(i, REQ_BIND);
            }
        } catch (Exception e) {
            toast("Couldn't add this widget");
        }
    }

    private void configureOrAdd(int id, AppWidgetProviderInfo info) {
        if (info != null && info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null);
                return;
            } catch (Exception ignored) {
            }
        }
        finishAddWidget();
    }

    private void finishAddWidget() {
        if (pendingWidget == null) return;
        store.addWidget("sys", pendingWidget[1], pendingWidget[2], pendingWidget[0], insertIndexForCurrentPage());
        pendingWidget = null;
        if (editing) setEditing(false);
        rebuild();
    }

    // ================================================================== edit mode & drag

    private LinearLayout buildEditBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView plus = pill("+", v -> showOverlay(new Sheets.WidgetPicker(this)));
        plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        plus.setPadding(Ui.dp(14), Ui.dp(2), Ui.dp(14), Ui.dp(3));
        bar.addView(plus);
        bar.addView(pill("Wallpaper", v -> showOverlay(new Sheets.WallpaperPicker(this))), withLeft(8));
        bar.addView(pill("⚙", v -> showSettings()), withLeft(8));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        bar.addView(pill("Done", v -> setEditing(false)));
        return bar;
    }

    private LinearLayout.LayoutParams withLeft(int dp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.leftMargin = Ui.dp(dp);
        return l;
    }

    private TextView pill(String t, View.OnClickListener l) {
        TextView v = Ui.text(this, t, 15, 0xFF1C1C1E, Ui.SEMIBOLD);
        v.setBackground(Ui.round(0xE6E5E5EA, 16));
        v.setPadding(Ui.dp(14), Ui.dp(7), Ui.dp(14), Ui.dp(7));
        v.setOnClickListener(l);
        return v;
    }

    void setEditing(boolean on) {
        if (editing == on) return;
        editing = on;
        editBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            editBar.setAlpha(0f);
            editBar.animate().alpha(1f).setDuration(200).start();
        }
        rebuild();
    }

    private boolean onDrag(View host, DragEvent e) {
        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return e.getLocalState() instanceof View;
            case DragEvent.ACTION_DRAG_LOCATION:
                edgeCheck(e.getX());
                return true;
            case DragEvent.ACTION_DROP:
                stopEdge();
                handleDrop((View) e.getLocalState(), e.getX(), e.getY());
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                stopEdge();
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                stopEdge();
                if (dragging != null) dragging.setAlpha(1f);
                dragging = null;
                return true;
            default:
                return true;
        }
    }

    private final Runnable edgeTick = new Runnable() {
        @Override
        public void run() {
            if (edgeDir == 0) return;
            int p = pager.getCurrent() + edgeDir;
            if (p >= 1 && p <= pages.size()) pager.setPage(p, true);
            main.postDelayed(this, 900);
        }
    };

    private void edgeCheck(float x) {
        int dir = x < Ui.dp(28) ? -1 : (x > lastW - Ui.dp(28) ? 1 : 0);
        if (dir != edgeDir) {
            stopEdge();
            edgeDir = dir;
            if (dir != 0) main.postDelayed(edgeTick, 600);
        }
    }

    private void stopEdge() {
        edgeDir = 0;
        main.removeCallbacks(edgeTick);
    }

    /** Is (x, y) — in homeLayer coordinates — inside v, shrunk by the given fractions? */
    private boolean hit(View v, float x, float y, float fx, float fyTop, float fyBottom) {
        int[] a = new int[2], b = new int[2];
        v.getLocationInWindow(a);
        homeLayer.getLocationInWindow(b);
        float lx = x + b[0] - a[0], ly = y + b[1] - a[1];
        float w = v.getWidth(), h = v.getHeight();
        return lx >= w * fx && lx < w * (1 - fx) && ly >= h * fyTop && ly < h * (1 - fyBottom);
    }

    private boolean hit(View v, float x, float y) {
        return hit(v, x, y, 0, 0, 0);
    }

    private int dockIndexAt(float x) {
        int n = dockRow.getChildCount();
        int[] a = new int[2], b = new int[2];
        homeLayer.getLocationInWindow(b);
        for (int i = 0; i < n; i++) {
            View c = dockRow.getChildAt(i);
            c.getLocationInWindow(a);
            float cx = a[0] - b[0] + c.getWidth() / 2f;
            if (x < cx) return i;
        }
        return n;
    }

    private static String entryOf(View v) {
        if (v instanceof AppIconView) return ((AppIconView) v).entry();
        if (v instanceof WidgetFrame) return ((WidgetFrame) v).entry;
        return null;
    }

    private static int indexOf(View v) {
        if (v instanceof AppIconView) return ((AppIconView) v).index;
        if (v instanceof WidgetFrame) return ((WidgetFrame) v).index;
        return -1;
    }

    private int pageEnd(int hp) {
        int max = -1;
        for (int p = 0; p <= hp && p < pages.size(); p++)
            for (Placed pl : pages.get(p)) max = Math.max(max, pl.index);
        return max + 1;
    }

    private void handleDrop(View src, float x, float y) {
        String entry = entryOf(src);
        if (entry == null) return;
        if (hit(dockFrame, x, y)) {
            if (AppStore.isApp(entry)) store.move(entry, true, dockIndexAt(x));
            requestRebuild();
            return;
        }
        int hp = pager.getCurrent() - 1;
        if (hp < 0 || hp >= pages.size()) return;
        for (View v : homeItems) {
            int idx = indexOf(v);
            if (v == src || idx < 0 || idx >= pageOfIndex.length || pageOfIndex[idx] != hp) continue;
            if (!hit(v, x, y)) continue;
            String target = store.home.get(idx);
            boolean center = v instanceof AppIconView && hit(v, x, y, 0.18f, 0.05f, 0.35f);
            if (AppStore.isApp(entry) && center) {
                AppIconView t = (AppIconView) v;
                if (t.isFolder()) store.addToFolder(t.folderId, entry);
                else store.createFolder(target, entry);
            } else {
                store.move(entry, false, idx);
            }
            requestRebuild();
            return;
        }
        int end = pageEnd(hp);
        int srcIdx = store.home.indexOf(entry);
        if (srcIdx >= 0 && srcIdx < end) end--;
        store.move(entry, false, end);
        requestRebuild();
    }

    // ================================================================== overlays

    private void showOverlay(View v) {
        closeOverlayNow();
        overlay = v;
        overlayLayer.addView(v, match());
        if (v instanceof Motion.OverlayAnim) {
            ((Motion.OverlayAnim) v).enter();
        } else {
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(200).start();
        }
        animateBlur(true);
    }

    void closeOverlay() {
        if (overlay == null) return;
        final View v = overlay;
        overlay = null;
        hideKeyboard(v);
        if (v instanceof Motion.OverlayAnim) {
            ((Motion.OverlayAnim) v).exit(() -> overlayLayer.removeView(v));
        } else {
            v.animate().alpha(0f).setDuration(170).withEndAction(() -> overlayLayer.removeView(v)).start();
        }
        animateBlur(false);
    }

    void closeOverlayNow() {
        if (overlay == null) return;
        if (overlay instanceof FolderOverlay) ((FolderOverlay) overlay).commitName();
        hideKeyboard(overlay);
        overlay.animate().cancel();
        overlayLayer.removeView(overlay);
        overlay = null;
        animateBlur(false);
    }

    /** Smoothly blurs and slightly shrinks the Home Screen behind an overlay, like iOS. */
    private void animateBlur(boolean on) {
        float to = on ? 1f : 0f;
        if (blurAnim != null) blurAnim.cancel();
        blurAnim = ValueAnimator.ofFloat(blurLevel, to);
        blurAnim.setDuration(on ? 260 : 220);
        blurAnim.setInterpolator(Motion.EASE_OUT);
        blurAnim.addUpdateListener(an -> {
            blurLevel = (float) an.getAnimatedValue();
            float s = 1f - 0.05f * blurLevel;
            homeLayer.setScaleX(s);
            homeLayer.setScaleY(s);
            if (Build.VERSION.SDK_INT >= 31) {
                float r = Ui.dp(24) * blurLevel;
                homeLayer.setRenderEffect(r < 0.5f ? null : RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
            }
        });
        blurAnim.start();
        if (Glass.wallpaper == null) windowBlur(on);
    }

    private void windowBlur(boolean on) {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (on && getWindowManager().isCrossWindowBlurEnabled()) {
                lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                lp.setBlurBehindRadius(Ui.dp(30));
            } else {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                lp.setBlurBehindRadius(0);
            }
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {
        }
    }

    void showSpotlight() {
        if (!loaded) return;
        showOverlay(new SpotlightView(this));
    }

    void showControlCenter() {
        showOverlay(new ControlCenter(this));
    }

    void showFolder(AppStore.Folder f, View origin) {
        showOverlay(new FolderOverlay(this, f, origin));
    }

    void expandNotifications() {
        try {
            Object sb = getSystemService("statusbar");
            Class<?> c = Class.forName("android.app.StatusBarManager");
            c.getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Throwable t) {
            toast("Swipe down from the very top to see notifications");
        }
    }

    // ================================================================== actions

    void launch(AppInfo a, View from) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(a.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Bundle opts = null;
            if (from != null && from.getWidth() > 0) {
                opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            }
            startActivity(i, opts);
            store.recordLaunch(a.key);
            launchedApp = true;
        } catch (Exception e) {
            toast("Can't open " + a.label);
        }
    }

    void webSearch(String q) {
        try {
            Intent i = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            openIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))));
        }
    }

    void openIntent(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("Not available on this phone");
        }
    }

    void openSettings(String action) {
        openIntent(new Intent(action));
    }

    void requestPerm(String perm, int code) {
        requestPermissions(new String[]{perm}, code);
    }

    void askWriteSettings() {
        openIntent(Widgets.writeSettingsIntent(this));
    }

    private void uninstall(String pkg) {
        openIntent(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    void showKeyboard(View v) {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
    }

    void hideKeyboard(View v) {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void setupTorch() {
        try {
            cameraManager = getSystemService(CameraManager.class);
            for (String id : cameraManager.getCameraIdList()) {
                Boolean f = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(f)) {
                    torchId = id;
                    break;
                }
            }
            torchCb = new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String id, boolean enabled) {
                    if (id.equals(torchId)) {
                        torchOn = enabled;
                        if (overlay instanceof ControlCenter) ((ControlCenter) overlay).refresh();
                    }
                }
            };
            cameraManager.registerTorchCallback(torchCb, main);
        } catch (Exception ignored) {
        }
    }

    void toggleTorch() {
        if (torchId == null || cameraManager == null) {
            toast("No flashlight found");
            return;
        }
        try {
            cameraManager.setTorchMode(torchId, !torchOn);
        } catch (Exception e) {
            toast("Flashlight is busy");
        }
    }

    // ================================================================== settings & welcome

    private void showSettings() {
        String[] items = {
                "Set SRK Launcher as Home app",
                "Wallpaper",
                "Add a widget",
                "Brightness & rotation control",
                "Do Not Disturb control",
                "Weather (location)",
                "Calendar events in widget",
                "Reset Home Screen layout"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("SRK Launcher")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            openSettings(Settings.ACTION_HOME_SETTINGS);
                            break;
                        case 1:
                            showOverlay(new Sheets.WallpaperPicker(this));
                            break;
                        case 2:
                            showOverlay(new Sheets.WidgetPicker(this));
                            break;
                        case 3:
                            askWriteSettings();
                            break;
                        case 4:
                            openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                            break;
                        case 5:
                            requestPerm(Manifest.permission.ACCESS_COARSE_LOCATION, REQ_LOCATION);
                            break;
                        case 6:
                            requestPerm(Manifest.permission.READ_CALENDAR, REQ_CALENDAR);
                            break;
                        case 7:
                            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                    .setTitle("Reset Home Screen?")
                                    .setMessage("Folders and widgets are removed; apps go back to alphabetical order.")
                                    .setPositiveButton("Reset", (d2, w2) -> {
                                        store.resetLayout();
                                        rebuild();
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private boolean isDefaultHome() {
        Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = getPackageManager().resolveActivity(h, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
    }

    private void maybeShowWelcome() {
        if (isFinishing() || isDestroyed()) return;
        SharedPreferences p = store.prefs;
        if (p.getBoolean("welcomed2", false) || isDefaultHome() && p.getBoolean("welcomed", false)) return;
        p.edit().putBoolean("welcomed", true).putBoolean("welcomed2", true).apply();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Welcome to SRK Launcher")
                .setMessage("• Swipe down in the middle: Search\n"
                        + "• Swipe down from the top-right: Control Centre\n"
                        + "• Swipe down from the top-left: Notification Centre\n"
                        + "• Swipe left past your last page: App Library\n"
                        + "• Swipe right from page 1: Today widgets\n"
                        + "• Long-press empty space: edit, add widgets (+), wallpaper\n"
                        + "• In edit mode drag an app onto another to make a folder")
                .setPositiveButton(isDefaultHome() ? "Got it" : "Set as Home app", (d, w) -> {
                    if (!isDefaultHome()) openSettings(Settings.ACTION_HOME_SETTINGS);
                })
                .setNegativeButton(isDefaultHome() ? null : "Later", null)
                .show();
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
