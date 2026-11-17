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

/** Installed apps plus the saved Home Screen / Dock layout. */
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

    final Context ctx;
    final SharedPreferences prefs;
    Map<String, AppInfo> apps = new LinkedHashMap<>();
    final ArrayList<String> home = new ArrayList<>();
    final ArrayList<String> dock = new ArrayList<>();

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

    /** Main thread: install a fresh app list and reconcile the saved layout. */
    void apply(Map<String, AppInfo> loaded) {
        apps = loaded;
        boolean first = !prefs.contains("home");
        Set<String> known = new HashSet<>(prefs.getStringSet("known", new HashSet<>()));
        home.clear();
        dock.clear();
        if (first) {
            for (Intent i : defaultDockIntents()) {
                String k = keyFor(i);
                if (k != null && !dock.contains(k) && dock.size() < DOCK_MAX) dock.add(k);
            }
            for (String k : apps.keySet()) if (!dock.contains(k)) home.add(k);
        } else {
            LinkedHashSet<String> h = new LinkedHashSet<>(split(prefs.getString("home", "")));
            LinkedHashSet<String> d = new LinkedHashSet<>(split(prefs.getString("dock", "")));
            for (String k : d) if (apps.containsKey(k) && dock.size() < DOCK_MAX) dock.add(k);
            for (String k : h) if (apps.containsKey(k) && !dock.contains(k)) home.add(k);
            // Newly installed apps go to the end of the Home Screen, like iOS.
            for (String k : apps.keySet()) {
                if (!known.contains(k) && !home.contains(k) && !dock.contains(k)) home.add(k);
            }
        }
        save();
    }

    void save() {
        prefs.edit()
                .putString("home", join(home))
                .putString("dock", join(dock))
                .putStringSet("known", new HashSet<>(apps.keySet()))
                .apply();
    }

    void resetLayout() {
        prefs.edit().remove("home").remove("dock").remove("known").apply();
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

    /** Moves an app to a final position in the Home Screen list or the Dock. */
    void move(String key, boolean toDock, int index) {
        boolean fromDock = dock.contains(key);
        int src = fromDock ? dock.indexOf(key) : home.indexOf(key);
        if (toDock && !fromDock && dock.size() >= DOCK_MAX) {
            int di = Math.max(0, Math.min(index, dock.size() - 1));
            String out = dock.get(di);
            dock.set(di, key);
            if (src >= 0) home.set(src, out); else home.add(out);
            save();
            return;
        }
        ArrayList<String> from = fromDock ? dock : home;
        ArrayList<String> to = toDock ? dock : home;
        if (src >= 0) from.remove(src);
        index = Math.max(0, Math.min(index, to.size()));
        to.add(index, key);
        save();
    }

    void removeFromHome(String key) {
        home.remove(key);
        dock.remove(key);
        save();
    }

    void addToHome(String key) {
        if (!home.contains(key) && !dock.contains(key)) home.add(key);
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

    List<Folder> folders() {
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

    private static List<String> split(String s) {
        ArrayList<String> l = new ArrayList<>();
        if (s == null || s.isEmpty()) return l;
        for (String p : s.split("\\|")) if (!p.isEmpty()) l.add(p);
        return l;
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) {
            if (b.length() > 0) b.append('|');
            b.append(s);
        }
        return b.toString();
    }
}
