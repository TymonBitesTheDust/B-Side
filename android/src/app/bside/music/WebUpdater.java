package app.bside.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the player up to date from the B-Side website (GitHub Pages) without a new APK.
 *
 * When B-Side opens, the page asks for a check (Settings → Update automatically). A newer page is downloaded into
 * the app's own files and used from the next start. It is still served from the app's own address, so the library,
 * playlists and settings carry over. Each page states its build number and the oldest Android part it works with
 * (meta tags bside-build and bside-native), so B-Side never goes back to an older page and never runs a page that
 * needs a newer APK. A downloaded page that fails to start three times in a row is set aside for the built-in one.
 */
final class WebUpdater {
    interface Ready {
        void onReady(String version);
    }

    static String url = "https://tymonbitesthedust.github.io/B-Side/index.html";
    private static final int MAX_BYTES = 12 * 1024 * 1024, MIN_BYTES = 100 * 1024, MAX_TRIES = 3;
    private static final long CHECK_EVERY_MS = 30 * 60 * 1000L;
    private static final Pattern META = Pattern.compile("<meta\\s+name=\"bside-([a-z]+)\"\\s+content=\"([^\"]*)\"");
    private static int bundled = -1;
    private static volatile boolean checking;

    // ───────────────────────── pure helpers (no Android) ─────────────────────────
    /** The bside-… meta tags near the top of a page: build, native, version. */
    static Map<String, String> meta(byte[] page) {
        Map<String, String> m = new HashMap<>();
        String head = new String(page, 0, Math.min(page.length, 16384), StandardCharsets.UTF_8);
        Matcher x = META.matcher(head);
        while (x.find()) m.put(x.group(1), x.group(2));
        return m;
    }

    static int num(String s) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Looks like a whole B-Side page, not an error page or a cut-off download. */
    static boolean complete(byte[] page) {
        if (page.length < MIN_BYTES) return false;
        String tail = new String(page, Math.max(0, page.length - 512), Math.min(512, page.length), StandardCharsets.UTF_8);
        return tail.contains("</html>") && num(meta(page).get("build")) > 0;
    }

    /** Whether a downloaded page should replace the built-in one. */
    static boolean usable(int build, int needsNative, int builtIn, int appVersion, int bad) {
        return build > builtIn && needsNative <= appVersion && build != bad;
    }

    // ───────────────────────── serving ─────────────────────────
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("webupdate", Context.MODE_PRIVATE);
    }

    private static File file(Context c) {
        return new File(new File(c.getFilesDir(), "web"), "index.html");
    }

    @SuppressWarnings("deprecation")
    static int appVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static synchronized int builtInBuild(Context c) {
        if (bundled >= 0) return bundled;
        try (InputStream in = c.getAssets().open("index.html")) {
            byte[] head = new byte[16384];
            int n = 0, r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) n += r;
            byte[] got = new byte[n];
            System.arraycopy(head, 0, got, 0, n);
            bundled = num(meta(got).get("build"));
        } catch (IOException e) {
            bundled = 0;
        }
        return bundled;
    }

    /** The page to show: a downloaded newer one when it's safe to use, otherwise the one built into the app. */
    static synchronized InputStream open(Context c) throws IOException {
        SharedPreferences p = prefs(c);
        File f = file(c);
        int build = p.getInt("build", 0);
        if (f.isFile() && usable(build, p.getInt("native", 0), builtInBuild(c), appVersion(c), p.getInt("bad", 0))) {
            int tries = p.getInt("trying", 0) == build ? p.getInt("tries", 0) : 0;
            if (tries >= MAX_TRIES) {
                p.edit().putInt("bad", build).apply(); // it never finished starting: go back to the built-in page
            } else {
                p.edit().putInt("trying", build).putInt("tries", tries + 1).apply();
                return new FileInputStream(f);
            }
        }
        return c.getAssets().open("index.html");
    }

    /** The page started properly. */
    static synchronized void started(Context c, int build) {
        SharedPreferences p = prefs(c);
        if (p.getInt("trying", 0) == build && p.getInt("tries", 0) != 0) p.edit().putInt("tries", 0).apply();
    }

    /** The version waiting for the next start, if it's newer than the page that's running; otherwise "". */
    static String readyVersion(Context c, int running) {
        SharedPreferences p = prefs(c);
        int build = p.getInt("build", 0);
        if (!file(c).isFile() || build <= running) return "";
        if (!usable(build, p.getInt("native", 0), builtInBuild(c), appVersion(c), p.getInt("bad", 0))) return "";
        return p.getString("version", "");
    }

    // ───────────────────────── checking ─────────────────────────
    /** Looks for a newer page on the website, at most every half hour. Runs on its own thread. */
    static void check(final Context c, final Ready ready) {
        final SharedPreferences p = prefs(c);
        if (checking || System.currentTimeMillis() - p.getLong("checked", 0) < CHECK_EVERY_MS) return;
        checking = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String version = download(c, p);
                    if (version != null && ready != null) ready.onReady(version);
                } catch (Exception ignored) {
                    // offline, or the site is down: try again next time
                } finally {
                    checking = false;
                }
            }
        }, "web-update").start();
    }

    /** Downloads the page if it changed. Returns the new version when one is ready for this app, else null. */
    private static String download(Context c, SharedPreferences p) throws IOException {
        File f = file(c);
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(30000);
        con.setUseCaches(false);
        con.setInstanceFollowRedirects(true);
        String etag = p.getString("etag", null);
        if (etag != null && f.isFile()) con.setRequestProperty("If-None-Match", etag);
        try {
            int code = con.getResponseCode();
            p.edit().putLong("checked", System.currentTimeMillis()).apply();
            if (code != 200) return null; // 304: nothing new
            ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 20);
            try (InputStream in = con.getInputStream()) {
                byte[] b = new byte[65536];
                for (int r; (r = in.read(b)) > 0; ) {
                    buf.write(b, 0, r);
                    if (buf.size() > MAX_BYTES) return null;
                }
            }
            byte[] page = buf.toByteArray();
            if (!complete(page)) return null;
            Map<String, String> m = meta(page);
            int build = num(m.get("build")), needs = num(m.get("native"));
            String tag = con.getHeaderField("ETag");
            if (build <= Math.max(builtInBuild(c), f.isFile() ? p.getInt("build", 0) : 0)) {
                if (tag != null) p.edit().putString("etag", tag).apply();
                return null; // not newer than what's here
            }
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return null;
            File tmp = new File(dir, "index.html.part");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(page);
                out.getFD().sync();
            }
            synchronized (WebUpdater.class) {
                if (!tmp.renameTo(f)) return null;
                SharedPreferences.Editor e = p.edit().putInt("build", build).putInt("native", needs)
                        .putString("version", m.containsKey("version") ? m.get("version") : String.valueOf(build))
                        .putInt("trying", 0).putInt("tries", 0);
                if (tag != null) e.putString("etag", tag);
                else e.remove("etag");
                e.apply();
            }
            return needs <= appVersion(c) ? p.getString("version", "") : null;
        } finally {
            con.disconnect();
        }
    }

    private WebUpdater() {
    }
}
