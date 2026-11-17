package in.shazverse.srklauncher;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.app.WallpaperManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.graphics.Insets;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SRK Launcher — an iOS-style home screen for Android. */
public class LauncherActivity extends Activity implements AppIconView.Callbacks, PagerView.Listener {

    static final int REQ_LOCATION = 11, REQ_CALENDAR = 12;

    AppStore store;
    int insetTop, insetBottom, lastW, lastH;
    int iconSize = 0;
    boolean torchOn;

    private static final int COLS = 4;
    private int rows = 6;
    private FrameLayout root, homeLayer, overlayLayer;
    private PagerView pager;
    private LinearLayout dots, dockRow, editBar;
    private FrameLayout dockFrame;
    private AppLibraryPage library;
    private final ArrayList<AppIconView> icons = new ArrayList<>();
    private final ArrayList<int[]> pageRanges = new ArrayList<>();
    private final ArrayList<Widgets.CalendarCard> calendars = new ArrayList<>();
    private boolean editing, loaded, pagerReady;
    private View overlay;
    private AppIconView dragging;
    private int edgeDir;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LauncherApps launcherApps;
    private LauncherApps.Callback laCallback;
    private CameraManager cameraManager;
    private String torchId;
    private CameraManager.TorchCallback torchCb;

    // ------------------------------------------------------------------ lifecycle

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

        pager = new PagerView(this);
        pager.setListener(this);
        homeLayer.addView(pager, match());

        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        homeLayer.addView(dots, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(20), Gravity.BOTTOM));

        dockFrame = new FrameLayout(this);
        dockFrame.setBackground(Ui.round(0x4DFFFFFF, 32));
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
        reload();
        main.postDelayed(this::maybeShowWelcome, 1200);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Weather.refresh(this, false);
        updateBadges();
        for (Widgets.CalendarCard c : calendars) c.load();
        if (overlay instanceof ControlCenter) ((ControlCenter) overlay).refresh();
    }

    @Override
    protected void onStop() {
        super.onStop();
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

    // ------------------------------------------------------------------ loading & layout

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

    private void relayout() {
        if (lastW == 0 || lastH == 0) return;
        int side = Ui.dp(14);
        int cellW = (lastW - side * 2) / COLS;
        iconSize = Math.min(Ui.dp(62), Math.round(cellW * 0.66f));
        int dockH = iconSize + Ui.dp(26);
        FrameLayout.LayoutParams dl = (FrameLayout.LayoutParams) dockFrame.getLayoutParams();
        dl.height = dockH;
        dl.leftMargin = dl.rightMargin = Ui.dp(10);
        dl.bottomMargin = insetBottom + Ui.dp(8);
        dockFrame.setLayoutParams(dl);
        FrameLayout.LayoutParams dotl = (FrameLayout.LayoutParams) dots.getLayoutParams();
        dotl.bottomMargin = dl.bottomMargin + dockH + Ui.dp(2);
        dots.setLayoutParams(dotl);
        editBar.setPadding(Ui.dp(16), insetTop + Ui.dp(4), Ui.dp(16), Ui.dp(4));
        int gridH = lastH - insetTop - Ui.dp(16) - bottomReserve();
        int cellH = iconSize + Ui.dp(34);
        rows = Math.max(4, Math.min(7, gridH / Math.max(1, cellH)));
        if (loaded) rebuild();
    }

    private int bottomReserve() {
        FrameLayout.LayoutParams dotl = (FrameLayout.LayoutParams) dots.getLayoutParams();
        return dotl.bottomMargin + Ui.dp(22);
    }

    private void rebuild() {
        if (lastW == 0 || iconSize == 0) return;
        int keep = pager.getCurrent();
        boolean wasLibrary = pager.getChildCount() > 0 && keep == pager.getChildCount() - 1;
        icons.clear();
        pageRanges.clear();
        calendars.clear();
        pager.removeAllViews();

        pager.addView(buildTodayPage());
        int cap1 = COLS * (rows - 2), cap = COLS * rows;
        int n = store.home.size(), i = 0, p = 0;
        do {
            int c = p == 0 ? cap1 : cap;
            int count = Math.max(0, Math.min(c, n - i));
            pageRanges.add(new int[]{i, count});
            pager.addView(buildHomePage(p, i, count));
            i += count;
            p++;
        } while (i < n);
        if (editing) {
            pageRanges.add(new int[]{n, 0});
            pager.addView(buildHomePage(p, n, 0));
        }
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
        updateBadges();
        onScrollPosition(target);
    }

    private View buildHomePage(int p, int start, int count) {
        IconGrid g = new IconGrid(this, COLS, rows);
        g.setPadding(Ui.dp(14), insetTop + Ui.dp(16), Ui.dp(14), bottomReserve());
        int slot = 0;
        if (p == 0) {
            g.add(new Widgets.WeatherCard(this), 0, 0, COLS, 2, Ui.dp(8));
            slot = COLS * 2;
        }
        for (int k = 0; k < count; k++) {
            int idx = start + k;
            AppInfo a = store.apps.get(store.home.get(idx));
            if (a == null) continue;
            AppIconView v = newIcon(a, true, AppIconView.HOME, idx);
            int s = slot + k;
            g.add(v, s % COLS, s / COLS, 1, 1, 0);
        }
        return g;
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

        TextView date = Ui.text(this, new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date()), 17, 0xE6FFFFFF, Ui.MEDIUM);
        date.setGravity(Gravity.CENTER);
        date.setShadowLayer(Ui.dp(3), 0, Ui.dp(1), 0x66000000);
        col.addView(date, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 76);
        clock.setTypeface(Ui.LIGHT);
        clock.setTextColor(Ui.WHITE);
        clock.setGravity(Gravity.CENTER);
        clock.setShadowLayer(Ui.dp(4), 0, Ui.dp(1), 0x66000000);
        col.addView(clock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView search = Ui.text(this, "Search", 17, 0x99FFFFFF, Ui.REGULAR);
        search.setBackground(Ui.round(Ui.FILL, 12));
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
        android.graphics.drawable.Drawable sd = Ui.icon(this, R.drawable.ic_search, 0x99FFFFFF);
        sd.setBounds(0, 0, Ui.dp(20), Ui.dp(20));
        search.setCompoundDrawablesRelative(sd, null, null, null);
        search.setCompoundDrawablePadding(Ui.dp(6));
        search.setOnClickListener(v -> showSpotlight());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(44));
        slp.topMargin = Ui.dp(14);
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
        sug.setBackground(Ui.round(Ui.CARD, 22));
        sug.setPadding(Ui.dp(8), Ui.dp(12), Ui.dp(8), Ui.dp(8));
        TextView st = Ui.text(this, "Suggestions", 14, 0xCCFFFFFF, Ui.MEDIUM);
        st.setPadding(Ui.dp(8), 0, 0, Ui.dp(6));
        sug.addView(st);
        LinearLayout icRow = new LinearLayout(this);
        for (AppInfo a : store.suggestions(4)) {
            AppIconView iv = newIcon(a, true, AppIconView.OTHER, -1);
            icRow.addView(iv, new LinearLayout.LayoutParams(0, iconSize + Ui.dp(32), 1));
        }
        sug.addView(icRow);
        col.addView(sug, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, gap));
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
        int n = pageRanges.size();
        if (n <= 1) return;
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

    void updateBadges() {
        // Notification badges removed: notification access gets sideloaded apps blocked by Play Protect.
    }

    // ------------------------------------------------------------------ pager callbacks

    @Override
    public void onPageChanged(int page) {
        if (library != null && page != pager.getChildCount() - 1) library.clearSearchIfActive();
    }

    @Override
    public void onScrollPosition(float pos) {
        int last = pageRanges.size();
        float a;
        if (pos < 1) a = Math.max(0f, pos);
        else if (pos > last) a = Math.max(0f, 1f - (pos - last));
        else a = 1f;
        dockFrame.setAlpha(a);
        dots.setAlpha(a);
        dockFrame.setTranslationY((1 - a) * Ui.dp(40));
        dockFrame.setVisibility(a <= 0.01f ? View.INVISIBLE : View.VISIBLE);
        for (int i = 0; i < dots.getChildCount(); i++) {
            float d = Math.abs(pos - (i + 1));
            dots.getChildAt(i).setAlpha(d < 0.5f ? 1f : 0.4f);
        }
        int n = pager.getChildCount();
        if (n > 1 && root.getWindowToken() != null) {
            try {
                WallpaperManager wm = WallpaperManager.getInstance(this);
                wm.setWallpaperOffsetSteps(1f / (n - 1), 1f);
                wm.setWallpaperOffsets(root.getWindowToken(), Math.max(0f, Math.min(1f, pos / (n - 1))), 0.5f);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public boolean canSwipeDown(int page) {
        return !editing && overlay == null && page >= 1 && page <= pageRanges.size();
    }

    @Override
    public void onSwipeDown(float x, float y) {
        int topZone = insetTop + Ui.dp(64);
        if (y < topZone) {
            if (x > lastW * 0.55f) showControlCenter();
            else expandNotifications();
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

    // ------------------------------------------------------------------ icon callbacks

    @Override
    public void onIconClick(AppIconView v) {
        launch(v.app, v);
    }

    @Override
    public boolean onIconLongClick(AppIconView v) {
        showAppMenu(v.app, v);
        return true;
    }

    @Override
    public void onIconRemove(AppIconView v) {
        final AppInfo a = v.app;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Remove “" + a.label + "”?")
                .setMessage("Removing from Home Screen will keep the app in your App Library.")
                .setPositiveButton("Remove from Home Screen", (d, w) -> {
                    store.removeFromHome(a.key);
                    rebuild();
                })
                .setNegativeButton("Delete App", (d, w) -> uninstall(a.pkg))
                .setNeutralButton("Cancel", null)
                .show();
    }

    @Override
    public void onIconStartDrag(AppIconView v) {
        if (!v.removable()) return;
        dragging = v;
        ClipData data = ClipData.newPlainText("app", v.app.key);
        v.startDragAndDrop(data, new View.DragShadowBuilder(v), v, 0);
        v.setAlpha(0.25f);
    }

    void showAppMenu(final AppInfo a, View anchor) {
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        final List<ShortcutInfo> shortcuts = getShortcuts(a.pkg);
        for (int i = 0; i < shortcuts.size() && i < 4; i++) {
            CharSequence l = shortcuts.get(i).getShortLabel();
            m.add(1, 100 + i, i, l == null ? "Shortcut" : l);
        }
        boolean inDock = store.dock.contains(a.key), inHome = store.home.contains(a.key);
        m.add(2, 1, 10, "Edit Home Screen");
        if (inDock) m.add(2, 2, 11, "Remove from Dock");
        else if (store.dock.size() < AppStore.DOCK_MAX) m.add(2, 3, 11, "Add to Dock");
        if (inHome) m.add(2, 4, 12, "Remove from Home Screen");
        else if (!inDock) m.add(2, 5, 12, "Add to Home Screen");
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
                    store.removeFromHome(a.key);
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

    // ------------------------------------------------------------------ edit mode & drag

    private LinearLayout buildEditBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(pill("Wallpaper", v -> openIntent(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"))));
        TextView s = pill("Settings", v -> showSettings());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = Ui.dp(8);
        bar.addView(s, sl);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        bar.addView(pill("Done", v -> setEditing(false)));
        return bar;
    }

    private TextView pill(String t, View.OnClickListener l) {
        TextView v = Ui.text(this, t, 15, 0xFF1C1C1E, Ui.MEDIUM);
        v.setBackground(Ui.round(0xE6E5E5EA, 16));
        v.setPadding(Ui.dp(14), Ui.dp(7), Ui.dp(14), Ui.dp(7));
        v.setOnClickListener(l);
        return v;
    }

    void setEditing(boolean on) {
        if (editing == on) return;
        editing = on;
        editBar.setVisibility(on ? View.VISIBLE : View.GONE);
        rebuild();
    }

    private boolean onDrag(View host, DragEvent e) {
        switch (e.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return e.getLocalState() instanceof AppIconView;
            case DragEvent.ACTION_DRAG_LOCATION:
                edgeCheck(e.getX());
                return true;
            case DragEvent.ACTION_DROP:
                stopEdge();
                handleDrop((AppIconView) e.getLocalState(), e.getX(), e.getY());
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
            if (p >= 1 && p <= pageRanges.size()) pager.setPage(p, true);
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

    private boolean hit(View v, float x, float y) {
        int[] a = new int[2], b = new int[2];
        v.getLocationInWindow(a);
        homeLayer.getLocationInWindow(b);
        float lx = x + b[0] - a[0], ly = y + b[1] - a[1];
        return lx >= 0 && ly >= 0 && lx < v.getWidth() && ly < v.getHeight();
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

    private void handleDrop(AppIconView src, float x, float y) {
        if (src == null) return;
        String key = src.app.key;
        if (hit(dockFrame, x, y)) {
            store.move(key, true, dockIndexAt(x));
            main.post(this::rebuild);
            return;
        }
        int hp = pager.getCurrent() - 1;
        if (hp < 0 || hp >= pageRanges.size()) return;
        int[] range = pageRanges.get(hp);
        for (AppIconView v : icons) {
            if (!AppIconView.HOME.equals(v.place) || v == src) continue;
            if (v.index < range[0] || v.index >= range[0] + range[1]) continue;
            if (hit(v, x, y)) {
                store.move(key, false, v.index);
                main.post(this::rebuild);
                return;
            }
        }
        int end = range[0] + range[1];
        int srcIdx = store.home.indexOf(key);
        if (srcIdx >= 0 && srcIdx < end) end--;
        store.move(key, false, end);
        main.post(this::rebuild);
    }

    // ------------------------------------------------------------------ overlays

    private void showOverlay(View v) {
        closeOverlayNow();
        overlay = v;
        overlayLayer.addView(v, match());
        v.setAlpha(0f);
        v.setTranslationY(-Ui.dp(24));
        v.animate().alpha(1f).translationY(0).setDuration(220).start();
        Ui.blur(homeLayer, true);
        windowBlur(true);
    }

    void closeOverlay() {
        if (overlay == null) return;
        final View v = overlay;
        overlay = null;
        hideKeyboard(v);
        v.animate().alpha(0f).translationY(-Ui.dp(16)).setDuration(170)
                .withEndAction(() -> overlayLayer.removeView(v)).start();
        Ui.blur(homeLayer, false);
        windowBlur(false);
    }

    private void closeOverlayNow() {
        if (overlay == null) return;
        hideKeyboard(overlay);
        overlayLayer.removeView(overlay);
        overlay = null;
        Ui.blur(homeLayer, false);
        windowBlur(false);
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

    void showFolder(AppStore.Folder f) {
        showOverlay(new FolderOverlay(this, f));
    }

    private void expandNotifications() {
        try {
            Object sb = getSystemService("statusbar");
            Class<?> c = Class.forName("android.app.StatusBarManager");
            c.getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Throwable t) {
            showSpotlight();
        }
    }

    // ------------------------------------------------------------------ actions

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

    // ------------------------------------------------------------------ settings & welcome

    private void showSettings() {
        String[] items = {
                "Set SRK Launcher as Home app",
                "Change wallpaper",
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
                            openIntent(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"));
                            break;
                        case 2:
                            askWriteSettings();
                            break;
                        case 3:
                            openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                            break;
                        case 4:
                            requestPerm(Manifest.permission.ACCESS_COARSE_LOCATION, REQ_LOCATION);
                            break;
                        case 5:
                            requestPerm(Manifest.permission.READ_CALENDAR, REQ_CALENDAR);
                            break;
                        case 6:
                            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                    .setTitle("Reset Home Screen?")
                                    .setMessage("All apps go back to alphabetical order and the default Dock.")
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
        if (p.getBoolean("welcomed", false) || isDefaultHome()) return;
        p.edit().putBoolean("welcomed", true).apply();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Welcome to SRK Launcher")
                .setMessage("Make it your Home app so the Home button always opens it.\n\n"
                        + "• Swipe down in the middle: Search\n"
                        + "• Swipe down from the top-right: Control Centre\n"
                        + "• Swipe left past your last page: App Library\n"
                        + "• Swipe right from page 1: Today widgets\n"
                        + "• Long-press empty space: edit Home Screen")
                .setPositiveButton("Set as Home app", (d, w) -> openSettings(Settings.ACTION_HOME_SETTINGS))
                .setNegativeButton("Later", null)
                .show();
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
