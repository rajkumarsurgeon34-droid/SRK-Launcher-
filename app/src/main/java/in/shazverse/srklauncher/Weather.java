package in.shazverse.srklauncher;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Free weather from Open-Meteo (no API key) for the phone's approximate location. */
final class Weather {
    private Weather() {}

    static boolean hasData;
    static String city = "";
    static double temp, hi, lo;
    static int code;
    static boolean isDay = true;
    static long updated;
    static String status = "Tap to show weather";

    static final ArrayList<Runnable> listeners = new ArrayList<>();
    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean busy;
    private static SharedPreferences prefs;

    static void init(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("weather", Context.MODE_PRIVATE);
        String j = prefs.getString("data", null);
        if (j != null) {
            try {
                JSONObject o = new JSONObject(j);
                city = o.optString("city", "");
                temp = o.getDouble("temp");
                hi = o.getDouble("hi");
                lo = o.getDouble("lo");
                code = o.getInt("code");
                isDay = o.optBoolean("day", true);
                updated = o.getLong("t");
                hasData = true;
            } catch (Exception ignored) {
            }
        }
        if (!hasPermission(c)) status = "Tap to allow location for weather";
    }

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static void notifyListeners() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    private static void fail(String msg) {
        MAIN.post(() -> {
            busy = false;
            status = msg;
            notifyListeners();
        });
    }

    static void refresh(Context ctx, boolean force) {
        final Context c = ctx.getApplicationContext();
        if (!hasPermission(c)) {
            status = "Tap to allow location for weather";
            notifyListeners();
            return;
        }
        if (busy) return;
        if (!force && hasData && System.currentTimeMillis() - updated < 20 * 60 * 1000L) return;
        busy = true;
        if (!hasData) {
            status = "Updating…";
            notifyListeners();
        }
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) {
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 6 * 3600 * 1000L) {
            fetch(c, best);
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                String prov = null;
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) prov = LocationManager.NETWORK_PROVIDER;
                else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) prov = LocationManager.GPS_PROVIDER;
                if (prov != null) {
                    final Location fallback = best;
                    lm.getCurrentLocation(prov, null, c.getMainExecutor(), loc -> {
                        Location use = loc != null ? loc : fallback;
                        if (use != null) fetch(c, use);
                        else fail("Location unavailable");
                    });
                    return;
                }
            } catch (SecurityException ignored) {
            }
        }
        if (best != null) fetch(c, best);
        else fail("Turn on location for weather");
    }

    private static void fetch(final Context c, final Location loc) {
        EX.execute(() -> {
            try {
                double lat = loc.getLatitude(), lon = loc.getLongitude();
                String url = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code,is_day"
                                + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
                        lat, lon);
                HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                h.setConnectTimeout(10000);
                h.setReadTimeout(10000);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                } finally {
                    h.disconnect();
                }
                JSONObject o = new JSONObject(sb.toString());
                JSONObject cur = o.getJSONObject("current");
                JSONObject d = o.getJSONObject("daily");
                final double t = cur.getDouble("temperature_2m");
                final int cd = cur.getInt("weather_code");
                final boolean day = cur.optInt("is_day", 1) == 1;
                final double hiV = d.getJSONArray("temperature_2m_max").getDouble(0);
                final double loV = d.getJSONArray("temperature_2m_min").getDouble(0);
                String name = null;
                try {
                    Geocoder g = new Geocoder(c, Locale.getDefault());
                    @SuppressWarnings("deprecation")
                    List<Address> a = g.getFromLocation(lat, lon, 1);
                    if (a != null && !a.isEmpty()) {
                        Address ad = a.get(0);
                        name = ad.getLocality();
                        if (name == null) name = ad.getSubAdminArea();
                        if (name == null) name = ad.getAdminArea();
                    }
                } catch (Exception ignored) {
                }
                if (name == null || name.isEmpty()) name = city.isEmpty() ? "My Location" : city;
                final String fname = name;
                MAIN.post(() -> {
                    city = fname;
                    temp = t;
                    code = cd;
                    isDay = day;
                    hi = hiV;
                    lo = loV;
                    updated = System.currentTimeMillis();
                    hasData = true;
                    busy = false;
                    try {
                        JSONObject s = new JSONObject();
                        s.put("city", city).put("temp", temp).put("hi", hi).put("lo", lo)
                                .put("code", code).put("day", isDay).put("t", updated);
                        prefs.edit().putString("data", s.toString()).apply();
                    } catch (Exception ignored) {
                    }
                    notifyListeners();
                });
            } catch (Exception e) {
                fail(hasData ? status : "Weather unavailable — tap to retry");
            }
        });
    }

    static String describe(int c) {
        if (c == 0) return "Clear";
        if (c == 1) return "Mostly Clear";
        if (c == 2) return "Partly Cloudy";
        if (c == 3) return "Cloudy";
        if (c == 45 || c == 48) return "Fog";
        if (c >= 51 && c <= 57) return "Drizzle";
        if (c >= 61 && c <= 67) return "Rain";
        if (c >= 71 && c <= 77) return "Snow";
        if (c >= 80 && c <= 82) return "Showers";
        if (c == 85 || c == 86) return "Snow Showers";
        if (c >= 95) return "Thunderstorm";
        return "—";
    }
}
