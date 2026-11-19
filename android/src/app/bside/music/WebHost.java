package app.bside.music;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Owns the player. The WebView that runs B-Side (and plays the music) lives here, at
 * process level, instead of inside the Activity. Closing the app, swiping it out of
 * Recents or Android destroying the screen only detaches the view; while a song is
 * playing the WebView keeps running under MusicService's foreground notification,
 * and the next time the app opens the same player is attached again, mid-song.
 */
final class WebHost {
    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST + "/";
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WebHost inst;

    final Context app;
    private MutableContextWrapper ctx;
    WebView web;
    MainActivity activity;
    private boolean pageReady;
    ValueCallback<Uri[]> fileCallback;
    volatile String pickMode = "files";
    volatile int insetTop, insetBottom;
    volatile boolean darkUi;
    volatile int bgColor;
    final Map<String, Uri> externalUris = Collections.synchronizedMap(new HashMap<String, Uri>());
    /** Files found while checking a folder again, served at /__scan/<key> so the page can add the new ones. */
    final Map<String, Uri> scanUris = Collections.synchronizedMap(new HashMap<String, Uri>());
    private int externalSeq;
    private JSONArray pendingOpen = new JSONArray();
    private boolean pendingOpenPlayer;
    private String pendingSpotify;
    private String pendingRemote;

    private WebHost(Context app) {
        this.app = app;
        darkUi = systemDark();
    }

    static WebHost get(Context c) {
        if (inst == null) inst = new WebHost(c.getApplicationContext());
        return inst;
    }

    static WebHost peek() {
        return inst;
    }

    // ───────────────────────── lifecycle ─────────────────────────
    /** Hands the (possibly already playing) WebView to a newly created Activity. */
    WebView attach(MainActivity a) {
        activity = a;
        if (web == null) create(a);
        else ctx.setBaseContext(a);
        ViewGroup parent = (ViewGroup) web.getParent();
        if (parent != null) parent.removeView(web);
        return web;
    }

    /** The Activity is going away. The WebView, and whatever it is playing, stays alive. */
    void detach(MainActivity a) {
        if (activity != a) return;
        activity = null;
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
        }
        if (ctx != null) ctx.setBaseContext(app);
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
    }

    /** Frees the player when nobody can see or hear it. */
    void destroyIfIdle() {
        if (activity == null && !MusicService.isPlaying()) destroy();
    }

    void destroy() {
        if (web == null) return;
        WebView w = web;
        web = null;
        pageReady = false;
        ViewGroup parent = (ViewGroup) w.getParent();
        if (parent != null) parent.removeView(w);
        try {
            w.stopLoading();
            w.removeJavascriptInterface("BSideNative");
            w.destroy();
        } catch (Exception ignored) {
        }
    }

    private void create(Context base) {
        ctx = new MutableContextWrapper(base);
        WebView w = new WebView(ctx);
        web = w;
        pageReady = false;
        w.setBackgroundColor(bgColor != 0 ? bgColor : (darkUi ? 0xFF030304 : 0xFFF3F3F6));
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        // Keep the page's renderer as important as the app itself, even while no screen shows it,
        // so Android doesn't reclaim it (and the music) in the background.
        if (Build.VERSION.SDK_INT >= 26) w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        w.addJavascriptInterface(new Bridge(), "BSideNative");
        w.setWebViewClient(new Client());
        w.setWebChromeClient(new Chrome());
        w.loadUrl(ORIGIN + "index.html");
    }

    // ───────────────────────── JS calls ─────────────────────────
    void js(final String code) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
            if (web != null && pageReady) web.evaluateJavascript(code, null);
        }
        });
    }

    /** Called by MusicService (notification buttons, headset, lock screen). */
    static void remote(String action, long value) {
        WebHost h = inst;
        if (h != null) h.js("window.bsideRemote&&bsideRemote('" + action + "'," + value + ")");
    }

    /**
     * Play, next or previous from outside the app. If the player isn't running (B-Side was closed and a car or
     * headset pressed Play), it is started without a screen and gets the command once the page is ready.
     * Main thread only.
     */
    static void command(Context c, String action) {
        if (!"play".equals(action) && !"next".equals(action) && !"prev".equals(action)) return;
        WebHost h = get(c);
        if (h.web != null && h.pageReady) {
            remote(action, 0);
            return;
        }
        h.pendingRemote = action;
        if (h.web == null) h.create(h.activity != null ? h.activity : h.app);
    }

    void setInsets(int top, int bottom) {
        insetTop = top;
        insetBottom = bottom;
        pushInsets();
    }

    private void pushInsets() {
        float dn = app.getResources().getDisplayMetrics().density;
        js("window.bsideInsets&&bsideInsets(" + (insetTop / dn) + "," + (insetBottom / dn) + ")");
    }

    boolean systemDark() {
        return (app.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    void onSystemDark(boolean d) {
        js("window.bsideSystemDark&&bsideSystemDark(" + d + ")");
    }

    void notice(String text) {
        js("window.bsideNotice&&bsideNotice(" + org.json.JSONObject.quote(text) + ")");
    }

    void deliver(Uri[] uris) {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(uris);
            fileCallback = null;
        }
    }

    void queueOpen(Uri uri, String name, String type) {
        try {
            String key = String.valueOf(++externalSeq);
            externalUris.put(key, uri);
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("url", ORIGIN + "__open/" + key);
            o.put("name", name);
            if (type != null) o.put("type", type);
            pendingOpen.put(o);
        } catch (Exception ignored) {
        }
    }

    void openPlayerWhenReady() {
        pendingOpenPlayer = true;
    }

    void flushPending() {
        if (!pageReady) return;
        if (pendingOpen.length() > 0) {
            js("window.bsideOpenExternal&&bsideOpenExternal(" + pendingOpen + ")");
            pendingOpen = new JSONArray();
        }
        if (pendingOpenPlayer) {
            pendingOpenPlayer = false;
            js("window.bsideOpenPlayer&&bsideOpenPlayer()");
        }
        if (pendingRemote != null) {
            js("window.bsideRemote&&bsideRemote('" + pendingRemote + "',0)");
            pendingRemote = null;
        }
        if (pendingSpotify != null) {
            js("window.bsideSpotifyCallback&&bsideSpotifyCallback(" + org.json.JSONObject.quote(pendingSpotify) + ")");
            pendingSpotify = null;
        }
    }

    // ───────────────────────── JS bridge ─────────────────────────
    final class Bridge {
        @JavascriptInterface
        public void update(String json) {
            MusicService.onJsUpdate(app, json);
        }

        @JavascriptInterface
        public void setPickMode(String mode) {
            pickMode = mode == null ? "files" : mode;
        }

        @JavascriptInterface
        public boolean isDark() {
            return systemDark();
        }

        @JavascriptInterface
        public void setDarkUi(final boolean d) {
            darkUi = d;
            MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (activity != null) activity.applySystemUi(d);
            }
        });
        }

        @JavascriptInterface
        public void setBackground(final String hex) {
            MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    bgColor = Color.parseColor(hex == null ? "" : hex.trim()) | 0xFF000000;
                    if (web != null) web.setBackgroundColor(bgColor);
                    if (activity != null) activity.getWindow().getDecorView().setBackgroundColor(bgColor);
                } catch (Exception ignored) {
                }
            }
        });
        }

        @JavascriptInterface
        public void setLauncherIcon(String id) {
            if (id == null || !MainActivity.isIcon(id)) return;
            SharedPreferences p = app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
            if (id.equals(MainActivity.currentIcon(app))) p.edit().remove(MainActivity.PENDING_ICON).apply();
            else p.edit().putString(MainActivity.PENDING_ICON, id).apply(); // applied when the app goes to the background
        }

        @JavascriptInterface
        public String launcherIcon() {
            String pending = app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getString(MainActivity.PENDING_ICON, null);
            return pending != null ? pending : MainActivity.currentIcon(app);
        }

        /** Whether Android lets B-Side keep running in the background (battery optimisation / restriction). */
        @JavascriptInterface
        public String backgroundState() {
            boolean ignoring = false, restricted = false;
            try {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                ignoring = pm.isIgnoringBatteryOptimizations(app.getPackageName());
                if (Build.VERSION.SDK_INT >= 28) {
                    ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
                    restricted = am.isBackgroundRestricted();
                }
            } catch (Exception ignored) {
            }
            return "{\"ignoring\":" + ignoring + ",\"restricted\":" + restricted
                    + ",\"maker\":" + org.json.JSONObject.quote(Build.MANUFACTURER == null ? "" : Build.MANUFACTURER) + "}";
        }

        /** Asks Android to stop optimising B-Side's battery use, or opens its app settings if that's already done. */
        @JavascriptInterface
        public void requestBackground() {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    Context c = activity != null ? activity : app;
                    Uri pkg = Uri.parse("package:" + app.getPackageName());
                    boolean ignoring = false;
                    try {
                        ignoring = ((PowerManager) app.getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(app.getPackageName());
                    } catch (Exception ignored) {
                    }
                    Intent i = ignoring ? new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
                            : new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg);
                    if (activity == null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(i);
                    } catch (Exception e) {
                        try {
                            Intent d = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
                            if (activity == null) d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            c.startActivity(d);
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
        }

        /** Opens a Spotify search: in the Spotify app when it's installed, otherwise on open.spotify.com in the browser. */
        @JavascriptInterface
        public void openSpotify(final String query) {
            final String q = query == null ? "" : query.trim();
            if (q.isEmpty()) return;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    Context c = activity != null ? activity : app;
                    Intent inApp = new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q)));
                    inApp.putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://" + app.getPackageName()));
                    if (activity == null) inApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(inApp);
                    } catch (Exception e) {
                        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/" + Uri.encode(q)));
                        if (activity == null) web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            c.startActivity(web);
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
        }

        /** Opens a web page in the browser: Spotify sign-in, or the Spotify developer dashboard. */
        @JavascriptInterface
        public void openUrl(final String url) {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    Context c = activity != null ? activity : app;
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    if (activity == null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(i);
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        /** Waits for Spotify's sign-in to come back to 127.0.0.1, then passes the code to the page. */
        @JavascriptInterface
        public boolean spotifyListen() {
            return LoopbackLogin.start(new LoopbackLogin.Listener() {
                @Override
                public void onCallback(final String query) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            pendingSpotify = query;
                            flushPending();
                        }
                    });
                }
            });
        }

        /** The page finished starting (so a downloaded page is known to work). */
        @JavascriptInterface
        public void pageStarted(int build) {
            WebUpdater.started(app, build);
        }

        /** Looks for a newer B-Side page on the website; tells the page when one is ready for the next start. */
        @JavascriptInterface
        public void checkUpdate() {
            WebUpdater.check(app, new WebUpdater.Ready() {
                @Override
                public void onReady(String version) {
                    js("window.bsideUpdateReady&&bsideUpdateReady(" + org.json.JSONObject.quote(version) + ")");
                }
            });
        }

        /** The version of a downloaded page that is newer than the running one, or "". */
        @JavascriptInterface
        public String updateReady(int runningBuild) {
            return WebUpdater.readyVersion(app, runningBuild);
        }

        /** Restarts the page now, so a downloaded update takes over. */
        @JavascriptInterface
        public void applyUpdate() {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    if (web != null) {
                        pageReady = false;
                        web.reload();
                    }
                }
            });
        }

        @JavascriptInterface
        public String insets() {
            float dn = app.getResources().getDisplayMetrics().density;
            return "{\"top\":" + (insetTop / dn) + ",\"bottom\":" + (insetBottom / dn) + "}";
        }

        // ── folders: checking them again for new and deleted songs ──

        /** The folders and single songs Android still lets B-Side read. */
        @JavascriptInterface
        public String access() {
            return FolderScan.access(app.getContentResolver());
        }

        /**
         * Reads a picked folder again, off the main thread, and answers bsideScanResult(token, result) with
         * {ok, partial, name, files:[{n: name, s: size, p: folder path, k: key for /__scan/}]} or {ok:false, why}.
         */
        @JavascriptInterface
        public void scanTree(final String uri, final String token) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    org.json.JSONObject res = new org.json.JSONObject();
                    try {
                        final Uri tree = Uri.parse(uri);
                        if (!FolderScan.persisted(app.getContentResolver(), tree)) {
                            res.put("ok", false).put("why", "denied");
                        } else {
                            String rootId = android.provider.DocumentsContract.getTreeDocumentId(tree);
                            final String root = FolderScan.rootName(app.getContentResolver(), tree, rootId);
                            final JSONArray files = new JSONArray();
                            final String pre = token + ".";
                            if (scanUris.size() > 60000) scanUris.clear();
                            boolean whole = FolderScan.walk(app.getContentResolver(), tree, rootId, root, new FolderScan.Sink() {
                                @Override
                                public void file(String docId, String name, long size, String path, boolean audio) {
                                    try {
                                        String key = pre + files.length();
                                        scanUris.put(key, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId));
                                        files.put(new org.json.JSONObject().put("n", name).put("s", size).put("p", path).put("k", key).put("a", audio ? 1 : 0));
                                    } catch (Exception ignored) {
                                    }
                                }
                            }, 0, new int[1]);
                            // A folder that can't be opened at all (storage unplugged, folder deleted) reads as empty
                            // and incomplete: the page then leaves its songs alone.
                            if (!whole && files.length() == 0) res.put("ok", false).put("why", "missing");
                            else res.put("ok", true).put("partial", !whole).put("name", root).put("files", files);
                        }
                    } catch (Exception e) {
                        try {
                            res.put("ok", false).put("why", e instanceof SecurityException ? "denied" : "error");
                        } catch (Exception ignored) {
                        }
                    }
                    js("window.bsideScanResult&&bsideScanResult(" + org.json.JSONObject.quote(token) + "," + res + ")");
                }
            }, "folder-rescan").start();
        }

        /** Checks songs that were picked one by one; answers bsideScanResult(token, {ok, files:[…]}). */
        @JavascriptInterface
        public void checkFiles(final String json, final String token) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String res;
                    try {
                        res = "{\"ok\":true,\"files\":" + FolderScan.check(app.getContentResolver(), new JSONArray(json)) + "}";
                    } catch (Exception e) {
                        res = "{\"ok\":false,\"why\":\"error\"}";
                    }
                    js("window.bsideScanResult&&bsideScanResult(" + org.json.JSONObject.quote(token) + "," + res + ")");
                }
            }, "file-check").start();
        }

        /** Gives back Android's lasting access to a folder or song B-Side no longer keeps. */
        @JavascriptInterface
        public void release(String uri) {
            try {
                app.getContentResolver().releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }

        /** A check of the folders has finished: forget the files it found. */
        @JavascriptInterface
        public void scanDone() {
            scanUris.clear();
        }

        // ── lock screen player ──

        /** {"on": chosen in Settings, "allowed": Android lets it appear over the lock screen, "maker": …} */
        @JavascriptInterface
        public String lockPlayer() {
            return "{\"on\":" + LockScreenActivity.enabled(app) + ",\"allowed\":" + LockScreenActivity.allowed(app)
                    + ",\"maker\":" + org.json.JSONObject.quote(Build.MANUFACTURER == null ? "" : Build.MANUFACTURER) + "}";
        }

        @JavascriptInterface
        public void setLockPlayer(boolean on) {
            LockScreenActivity.setEnabled(app, on);
        }

        /** Opens Android's "Display over other apps" page for B-Side; Android 10 and later need it to show the player. */
        @JavascriptInterface
        public void lockPermission() {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    Context c = activity != null ? activity : app;
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + app.getPackageName()));
                    if (activity == null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(i);
                    } catch (Exception e) {
                        try {
                            Intent d = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.getPackageName()));
                            if (activity == null) d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            c.startActivity(d);
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
        }
    }

    // ───────────────────────── WebView clients ─────────────────────────
    private static String mimeFor(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "text/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
            try {
                if (path.startsWith("/__open/") || path.startsWith("/__scan/")) {
                    Uri src = (path.startsWith("/__open/") ? externalUris : scanUris).get(path.substring(8));
                    if (src == null) return notFound();
                    InputStream in = app.getContentResolver().openInputStream(src);
                    if (in == null) return notFound();
                    String type = app.getContentResolver().getType(src);
                    return new WebResourceResponse(type == null ? "application/octet-stream" : type, null, in);
                }
                String asset = path.substring(1);
                if (asset.contains("..")) return notFound();
                InputStream body = "index.html".equals(asset) ? WebUpdater.open(app) : app.getAssets().open(asset); // a newer page from the website, if one was downloaded
                WebResourceResponse r = new WebResourceResponse(mimeFor(asset), "utf-8", body);
                Map<String, String> h = new HashMap<>();
                h.put("Cache-Control", "no-cache");
                r.setResponseHeaders(h);
                return r;
            } catch (IOException | SecurityException e) {
                return notFound();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (HOST.equals(u.getHost())) return false;
            try {
                app.startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (view != web) return;
            pageReady = true;
            pushInsets();
            onSystemDark(systemDark());
            flushPending();
        }

        /** The page's renderer crashed or was reclaimed: rebuild instead of taking the whole app down. */
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view == web) {
                destroy();
                MusicService.stop(app);
                if (activity != null) activity.reattach();
            } else {
                try {
                    view.destroy();
                } catch (Exception ignored) {
                }
            }
            return true;
        }
    }

    final class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (activity == null) return false;
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            String mode = pickMode;
            pickMode = "files";
            if (activity.launchPicker(mode, params)) return true;
            fileCallback = null;
            return false;
        }
    }
}
