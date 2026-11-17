package in.shazverse.srklauncher;

import android.content.ComponentName;
import android.graphics.Bitmap;

/** One launchable app. */
final class AppInfo {
    final ComponentName component;
    final String key;
    final String pkg;
    final String label;
    final int category;
    final long installTime;
    final boolean system;
    final Bitmap icon;

    AppInfo(ComponentName component, String label, int category, long installTime, boolean system, Bitmap icon) {
        this.component = component;
        this.key = component.flattenToShortString();
        this.pkg = component.getPackageName();
        this.label = label;
        this.category = category;
        this.installTime = installTime;
        this.system = system;
        this.icon = icon;
    }
}
