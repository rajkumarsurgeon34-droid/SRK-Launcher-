package in.shazverse.srklauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Installed apps plus the saved Home Screen layout.
 * Home entries are strings: an app key ("pkg/.Activity"), "folder:ID" or "widget:KIND:WxH:ID".
 */
final class AppStore {
    static final int DOCK_MAX = 4;

    static final class Folder {
        final String name;
        final List<AppInfo> apps;

        Folder(String name, List<AppInfo> apps) {
            this.name = name;
            this.apps = apps;
        }
    }

    /** A folder on the Home Screen. */
    static final class HomeFolder {
        final String id;
        String name;
        final ArrayList<String> items = new ArrayList<>();

        HomeFolder(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** A widget placed on the Home Screen. */
    static final class WidgetSpec {
        final String kind; // weather, clock, calendar, battery, sys
        final int w, h;
        final int id;

        WidgetSpec(String kind, int w, int h, int id) {
            this.kind = kind;
            this.w = w;
            this.h = h;
            this.id = id;
        }

        String entry() {
            return "widget:" + kind + ":" + w + "x" + h + ":" + id;
        }

        static WidgetSpec parse(String e) {
            try {
                String[] p = e.split(":");
                String[] wh = p[2].split("x");
                return new WidgetSpec(p[1], Integer.parseInt(wh[0]), Integer.parseInt(wh[1]), Integer.parseInt(p[3]));
            } catch (Exception ex) {
                return null;
            }
        }
    }

    static boolean isFolder(String e) {
        return e.startsWith("folder:");
    }

    static boolean isWidget(String e) {
        return e.startsWith("widget:");
    }

    static boolean isApp(String e) {
        return !isFolder(e) && !isWidget(e);
    }

    final Context ctx;
    final SharedPreferences prefs;
    Map<String, AppInfo> apps = new LinkedHashMap<>();
    final ArrayList<String> home = new ArrayList<>();
    final ArrayList<String> dock = new ArrayList<>();
    final LinkedHashMap<String, HomeFolder> folders = new LinkedHashMap<>();

    AppStore(Context c) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences("srk_launcher", Context.MODE_PRIVATE);
    }

    /** Runs on a background thread. */
    static Map<String, AppInfo> loadApps(Context ctx, int iconPx) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        ArrayList<AppInfo> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ctx.getPackageName().equals(ai.packageName)) continue;
            ComponentName cn = new ComponentName(ai.packageName, ai.name);
            if (!seen.add(cn.flattenToShortString())) continue;
            CharSequence lab = ri.loadLabel(pm);
            String label = lab == null ? ai.packageName : lab.toString().trim();
            long installed = 0;
            try {
                installed = pm.getPackageInfo(ai.packageName, 0).firstInstallTime;
            } catch (Exception ignored) {
            }
            ApplicationInfo app = ai.applicationInfo;
            boolean system = app != null && (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            int cat = Categories.categorize(app, ai.packageName, label);
            Bitmap icon;
            try {
                icon = IconFactory.make(ri.loadIcon(pm), iconPx);
            } catch (Throwable t) {
                icon = IconFactory.make(null, iconPx);
            }
            out.add(new AppInfo(cn, label, cat, installed, system, icon));
        }
        final Collator col = Collator.getInstance();
        col.setStrength(Collator.PRIMARY);
        Collections.sort(out, (a, b) -> col.compare(a.label, b.label));
        LinkedHashMap<String, AppInfo> map = new LinkedHashMap<>();
        for (AppInfo a : out) map.put(a.key, a);
        return map;
    }

    private static int newId() {
        return (int) (Math.random() * 1_000_000_000) + 1;
    }

    /** Main thread: install a fresh app list and reconcile the saved layout. */
    void apply(Map<String, AppInfo> loaded) {
        apps = loaded;
        boolean first = !prefs.contains("home");
        Set<String> known = new HashSet<>(prefs.getStringSet("known", new HashSet<>()));
        home.clear();
        dock.clear();
        folders.clear();
        if (first) {
            for (Intent i : defaultDockIntents()) {
                String k = keyFor(i);
                if (k != null && !dock.contains(k) && dock.size() < DOCK_MAX) dock.add(k);
            }
            home.add(new WidgetSpec("weather", 4, 2, newId()).entry());
            for (String k : apps.keySet()) if (!dock.contains(k)) home.add(k);
            prefs.edit().putBoolean("v2", true).apply();
        } else {
            for (String id : split(prefs.getString("folders", ""))) {
                HomeFolder f = new HomeFolder(id, prefs.getString("fname:" + id, "Folder"));
                for (String k : split(prefs.getString("fitems:" + id, ""))) {
                    if (apps.containsKey(k) && !f.items.contains(k)) f.items.add(k);
                }
                folders.put(id, f);
            }
            Set<String> inFolders = new HashSet<>();
            for (HomeFolder f : folders.values()) inFolders.addAll(f.items);

            for (String k : new LinkedHashSet<>(split(prefs.getString("dock", "")))) {
                if (apps.containsKey(k) && dock.size() < DOCK_MAX && !inFolders.contains(k)) dock.add(k);
            }
            boolean hasWidget = false;
            for (String e : new LinkedHashSet<>(split(prefs.getString("home", "")))) {
                if (isWidget(e)) {
                    if (WidgetSpec.parse(e) != null) {
                        home.add(e);
                        hasWidget = true;
                    }
                } else if (isFolder(e)) {
                    HomeFolder f = folders.get(e.substring(7));
                    if (f == null || f.items.isEmpty()) continue;
                    if (f.items.size() == 1) {
                        home.add(f.items.get(0));
                        folders.remove(f.id);
                    } else {
                        home.add(e);
                    }
                } else if (apps.containsKey(e) && !dock.contains(e) && !inFolders.contains(e)) {
                    home.add(e);
                }
            }
            // drop folders that are no longer on the Home Screen
            ArrayList<String> orphan = new ArrayList<>();
            for (String id : folders.keySet()) if (!home.contains("folder:" + id)) orphan.add(id);
            for (String id : orphan) folders.remove(id);

            if (!prefs.getBoolean("v2", false)) {
                if (!hasWidget) home.add(0, new WidgetSpec("weather", 4, 2, newId()).entry());
                prefs.edit().putBoolean("v2", true).apply();
            }
            Set<String> placed = new HashSet<>(home);
            placed.addAll(dock);
            for (HomeFolder f : folders.values()) placed.addAll(f.items);
            // Newly installed apps go to the end of the Home Screen, like iOS.
            for (String k : apps.keySet()) {
                if (!known.contains(k) && !placed.contains(k)) home.add(k);
            }
        }
        save();
    }

    void save() {
        SharedPreferences.Editor e = prefs.edit()
                .putString("home", join(home))
                .putString("dock", join(dock))
                .putStringSet("known", new HashSet<>(apps.keySet()))
                .putString("folders", join(new ArrayList<>(folders.keySet())));
        for (HomeFolder f : folders.values()) {
            e.putString("fname:" + f.id, f.name).putString("fitems:" + f.id, join(f.items));
        }
        e.apply();
    }

    void resetLayout() {
        prefs.edit().remove("home").remove("dock").remove("known").remove("folders").apply();
        apply(apps);
    }

    private List<Intent> defaultDockIntents() {
        ArrayList<Intent> l = new ArrayList<>();
        l.add(new Intent(Intent.ACTION_DIAL));
        l.add(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING));
        l.add(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).addCategory(Intent.CATEGORY_BROWSABLE));
        l.add(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
        return l;
    }

    private String keyFor(Intent i) {
        PackageManager pm = ctx.getPackageManager();
        try {
            ResolveInfo r = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            String p = (r != null && r.activityInfo != null) ? r.activityInfo.packageName : null;
            String k = p == null ? null : keyForPkg(p);
            if (k != null) return k;
            for (ResolveInfo q : pm.queryIntentActivities(i, 0)) {
                if (q.activityInfo == null) continue;
                k = keyForPkg(q.activityInfo.packageName);
                if (k != null) return k;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String keyForPkg(String pkg) {
        for (AppInfo a : apps.values()) if (a.pkg.equals(pkg)) return a.key;
        return null;
    }

    // ---- layout edits ----

    /** Moves an entry to a final position in the Home Screen list or (apps only) the Dock. */
    void move(String entry, boolean toDock, int index) {
        if (toDock && !isApp(entry)) return;
        boolean fromDock = dock.contains(entry);
        int src = fromDock ? dock.indexOf(entry) : home.indexOf(entry);
        if (toDock && !fromDock && dock.size() >= DOCK_MAX) {
            int di = Math.max(0, Math.min(index, dock.size() - 1));
            String out = dock.get(di);
            dock.set(di, entry);
            if (src >= 0) home.set(src, out); else home.add(out);
            save();
            return;
        }
        ArrayList<String> from = fromDock ? dock : home;
        ArrayList<String> to = toDock ? dock : home;
        if (src >= 0) from.remove(src);
        index = Math.max(0, Math.min(index, to.size()));
        to.add(index, entry);
        save();
    }

    void removeEntry(String entry) {
        home.remove(entry);
        dock.remove(entry);
        if (isFolder(entry)) folders.remove(entry.substring(7));
        save();
    }

    void addToHome(String key) {
        if (!home.contains(key) && !dock.contains(key) && folderOf(key) == null) home.add(key);
        save();
    }

    void addToDock(String key) {
        if (dock.contains(key) || dock.size() >= DOCK_MAX) return;
        home.remove(key);
        dock.add(key);
        save();
    }

    void removeFromDock(String key) {
        if (dock.remove(key) && !home.contains(key)) home.add(key);
        save();
    }

    // ---- folders ----

    HomeFolder folderOf(String key) {
        for (HomeFolder f : folders.values()) if (f.items.contains(key)) return f;
        return null;
    }

    /** Drop app {@code dragged} onto app {@code target}: make a folder named after the category. */
    String createFolder(String target, String dragged) {
        int idx = home.indexOf(target);
        if (idx < 0 || target.equals(dragged)) return null;
        AppInfo t = apps.get(target);
        String name = t == null ? "Folder" : Categories.NAMES[t.category].split(" & ")[0];
        if ("Other".equals(name)) name = "Folder";
        String id = String.valueOf(newId());
        HomeFolder f = new HomeFolder(id, name);
        f.items.add(target);
        f.items.add(dragged);
        home.set(idx, "folder:" + id);
        home.remove(dragged);
        dock.remove(dragged);
        folders.put(id, f);
        save();
        return id;
    }

    void addToFolder(String folderId, String key) {
        HomeFolder f = folders.get(folderId);
        if (f == null || f.items.contains(key)) return;
        home.remove(key);
        dock.remove(key);
        f.items.add(key);
        save();
    }

    /** Takes an app out of a folder; a folder left with one app dissolves, like iOS. */
    void removeFromFolder(String folderId, String key) {
        HomeFolder f = folders.get(folderId);
        if (f == null) return;
        f.items.remove(key);
        int idx = home.indexOf("folder:" + folderId);
        if (f.items.size() <= 1) {
            folders.remove(folderId);
            if (idx >= 0) {
                if (f.items.size() == 1) home.set(idx, f.items.get(0));
                else home.remove(idx);
            }
            idx = home.indexOf(f.items.isEmpty() ? key : f.items.get(0));
        }
        home.add(idx >= 0 ? Math.min(idx + 1, home.size()) : home.size(), key);
        save();
    }

    /** Moves every app out of a folder onto the Home Screen. */
    void dissolveFolder(String folderId) {
        HomeFolder f = folders.remove(folderId);
        if (f == null) return;
        int idx = home.indexOf("folder:" + folderId);
        if (idx < 0) idx = home.size(); else home.remove(idx);
        home.addAll(Math.min(idx, home.size()), f.items);
        save();
    }

    void renameFolder(String folderId, String name) {
        HomeFolder f = folders.get(folderId);
        if (f == null) return;
        f.name = name.trim().isEmpty() ? "Folder" : name.trim();
        save();
    }

    List<AppInfo> folderApps(String folderId) {
        ArrayList<AppInfo> l = new ArrayList<>();
        HomeFolder f = folders.get(folderId);
        if (f == null) return l;
        for (String k : f.items) {
            AppInfo a = apps.get(k);
            if (a != null) l.add(a);
        }
        return l;
    }

    // ---- widgets ----

    String addWidget(String kind, int w, int h, int id, int index) {
        String e = new WidgetSpec(kind, w, h, id == 0 ? newId() : id).entry();
        home.add(Math.max(0, Math.min(index, home.size())), e);
        save();
        return e;
    }

    String resizeWidget(String entry, int w, int h) {
        WidgetSpec s = WidgetSpec.parse(entry);
        int idx = home.indexOf(entry);
        if (s == null || idx < 0) return entry;
        String e = new WidgetSpec(s.kind, w, h, s.id).entry();
        home.set(idx, e);
        save();
        return e;
    }

    // ---- usage, suggestions, library ----

    void recordLaunch(String key) {
        prefs.edit().putInt("n:" + key, launches(key) + 1).apply();
    }

    int launches(String key) {
        return prefs.getInt("n:" + key, 0);
    }

    List<AppInfo> suggestions(int n) {
        ArrayList<AppInfo> all = new ArrayList<>(apps.values());
        final Map<String, Integer> counts = new HashMap<>();
        for (AppInfo a : all) counts.put(a.key, launches(a.key));
        all.sort((x, y) -> Integer.compare(counts.get(y.key), counts.get(x.key)));
        ArrayList<AppInfo> out = new ArrayList<>();
        for (AppInfo a : all) {
            if (out.size() >= n) break;
            if (counts.get(a.key) > 0) out.add(a);
        }
        for (String k : dock) {
            if (out.size() >= n) break;
            AppInfo a = apps.get(k);
            if (a != null && !out.contains(a)) out.add(a);
        }
        for (String k : home) {
            if (out.size() >= n) break;
            AppInfo a = apps.get(k);
            if (a != null && !out.contains(a)) out.add(a);
        }
        return out;
    }

    List<AppInfo> recentlyAdded(int n) {
        ArrayList<AppInfo> l = new ArrayList<>();
        for (AppInfo a : apps.values()) if (!a.system && a.installTime > 0) l.add(a);
        l.sort((x, y) -> Long.compare(y.installTime, x.installTime));
        return new ArrayList<>(l.subList(0, Math.min(n, l.size())));
    }

    List<Folder> libraryFolders() {
        ArrayList<Folder> out = new ArrayList<>();
        List<AppInfo> sug = suggestions(8);
        if (!sug.isEmpty()) out.add(new Folder("Suggestions", sug));
        List<AppInfo> rec = recentlyAdded(8);
        if (!rec.isEmpty()) out.add(new Folder("Recently Added", rec));
        Map<Integer, List<AppInfo>> byCat = new HashMap<>();
        for (AppInfo a : apps.values()) {
            List<AppInfo> l = byCat.get(a.category);
            if (l == null) {
                l = new ArrayList<>();
                byCat.put(a.category, l);
            }
            l.add(a);
        }
        final Map<String, Integer> counts = new HashMap<>();
        for (AppInfo a : apps.values()) counts.put(a.key, launches(a.key));
        for (int cat : Categories.ORDER) {
            List<AppInfo> l = byCat.get(cat);
            if (l == null || l.isEmpty()) continue;
            l.sort((x, y) -> Integer.compare(counts.get(y.key), counts.get(x.key)));
            out.add(new Folder(Categories.NAMES[cat], l));
        }
        return out;
    }

    List<AppInfo> search(String q) {
        String s = q.toLowerCase(Locale.getDefault()).trim();
        ArrayList<AppInfo> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (AppInfo a : apps.values()) {
            String l = a.label.toLowerCase(Locale.getDefault());
            if (l.startsWith(s)) starts.add(a);
            else if (l.contains(s) || containsWordStart(l, s)) contains.add(a);
        }
        starts.addAll(contains);
        return starts;
    }

    private static boolean containsWordStart(String label, String q) {
        for (String w : label.split("\\s+")) if (w.startsWith(q)) return true;
        return false;
    }

    static List<String> split(String s) {
        ArrayList<String> l = new ArrayList<>();
        if (s == null || s.isEmpty()) return l;
        for (String p : s.split("\\|")) if (!p.isEmpty()) l.add(p);
        return l;
    }

    static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) {
            if (b.length() > 0) b.append('|');
            b.append(s);
        }
        return b.toString();
    }
}
