package app.bside.music;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * The lock screen player (Settings → Lock screen player). When the screen goes off while music plays (or a few
 * minutes after it was paused), MusicService puts this screen over Android's lock screen: the time, the cover, the
 * song and big controls. It only ever shows
 * playback controls; swiping up (or tapping the cover) asks Android to unlock as usual. It closes by itself once
 * the phone is unlocked, or when there's nothing playing.
 *
 * What it shows is B-Side's own page opened in lock mode (web/lock.js), so it wears the style chosen in
 * Settings › Lock screen exactly as the app does, with the pieces chosen there. The page runs in a WebView of its
 * own, on an origin of its own (lock.appassets.androidplatform.net), so it can't see the library; it gets the song
 * and the look through the BSideLock bridge and nothing else loads. If no WebView can be made, a plain native
 * screen stands in.
 *
 * Android 10 and later only let an app open a screen from the background with "Display over other apps", so the
 * setting asks for that permission.
 */
public class LockScreenActivity extends Activity {
    private static final String PREF = "lockPlayer", PREF_LOOK = "lockLook", PREF_BLOCKED = "lockBlocked", PREF_BLOCKED_AT = "lockBlockedAt";
    private static final long CHECK_OPENED_MS = 3000, UNSTICK_MS = 12000, PAGE_READY_MS = 8000;
    // How often the lock screen is checked while it covers it, and how soon after unlocking a cover counts as the
    // phone's own (some phones put the home screen over everything as they unlock).
    private static final long WATCH_MS = 50, JUST_UNLOCKED_MS = 3000;
    private static final String EXTRA_BACK = "app.bside.music.LOCK_BACK";
    private static final String LOCK_HOST = "lock.appassets.androidplatform.net";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static LockScreenActivity shown;
    // When the screen was last asked for and last came up, to notice when Android quietly refuses to open it.
    private static long askedAt, openedAt;
    private static Runnable checkOpened;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateTime();
            MAIN.postDelayed(this, 500);
        }
    };
    private final Runnable checkLocked = new Runnable() {
        @Override
        public void run() {
            closeIfUnlocked();
        }
    };
    /** An unlock that Android never answered (it can happen if the screen goes off during the PIN): let it go. */
    private final Runnable unstick = new Runnable() {
        @Override
        public void run() {
            if (!leaving || left) return;
            leaving = false;
            settle();
            resetPage();
        }
    };
    /** The page didn't come up (or didn't say so) in time: the native screen instead, never a blank one. */
    private final Runnable pageTimeout = new Runnable() {
        @Override
        public void run() {
            if (web != null && !webDrawn) useNative();
        }
    };
    /**
     * While it covers the lock screen: notices the moment the phone unlocks (face, fingerprint, Smart Lock), well
     * before Android's "unlocked" message arrives, so it can close before it's seen over the home screen.
     */
    private final Runnable watchUnlock = new Runnable() {
        @Override
        public void run() {
            if (leaving || left || isFinishing()) return;
            if (locked()) {
                lockedSeenAt = SystemClock.elapsedRealtime();
                MAIN.postDelayed(this, WATCH_MS);
            } else if (lockedSeenAt > 0) {
                unlocked();
            }
        }
    };
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_USER_PRESENT.equals(i.getAction())) {
                // Unlocked: by fingerprint, face or PIN, or by the swipe up (whose answer may come after this).
                if (leaving) leave(openAfterUnlock);
                else unlocked();
            } else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                watch();
                MAIN.removeCallbacks(checkLocked);
                MAIN.postDelayed(checkLocked, 450);
            }
        }
    };

    private float dn;
    private WebView web; // the lock screen page; null when it couldn't be made (then the native views below)
    private boolean webReady, webDrawn;
    private int insetTop, insetBottom;
    private View content;
    private ImageView bg, art;
    private TextView title, sub, tCur, tDur;
    private SeekBar seek;
    private ImageButton play;
    private boolean seeking, leaving, left, openAfterUnlock, listening;
    private long lockedSeenAt; // when the lock screen was last seen under this one (0: not since it was asked for)
    private int broughtBack; // times it came back over the phone's own unlock screen (see onStop)
    private Bitmap shownCover;
    private int shownColor;
    private String shownKey;

    // ───────────────────────── called by MusicService and the page ─────────────────────────
    static boolean enabled(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getBoolean(PREF, false);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putBoolean(PREF, on).apply();
        if (!on) MAIN.post(new Runnable() {
            @Override
            public void run() {
                close();
            }
        });
    }

    /** The style and pieces chosen in Settings › Lock screen, as JSON from the page (see lock.js). */
    static String look(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString(PREF_LOOK, "");
    }

    static void setLook(Context c, String json) {
        c.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putString(PREF_LOOK, json == null ? "" : json).apply();
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        });
    }

    /** "Stay after unlocking" in Settings › Lock screen: face or fingerprint unlock doesn't close it. */
    static boolean stays(Context c) {
        String j = look(c);
        if (j.isEmpty()) return false;
        try {
            return new JSONObject(j).optBoolean("stay", false);
        } catch (Exception e) {
            return false;
        }
    }

    /** How many times in a row Android didn't let the screen open, and when that last happened. */
    static int blocked(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt(PREF_BLOCKED, 0);
    }

    static long blockedAt(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getLong(PREF_BLOCKED_AT, 0);
    }

    /** Whether Android lets B-Side put this screen up from the background. */
    static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(c);
    }

    static void show(Context c) {
        if (!enabled(c)) return;
        // Always asked for, even when one is open: it may be out of sight (after a call, or behind another app),
        // and asking brings that one back to the front (singleInstance → onNewIntent) instead of leaving it there.
        final Context app = c.getApplicationContext();
        Intent i = new Intent(app, LockScreenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        askedAt = SystemClock.elapsedRealtime();
        try {
            app.startActivity(i);
        } catch (Exception e) {
            noteOpened(app, false);
            return;
        }
        if (checkOpened != null) MAIN.removeCallbacks(checkOpened);
        checkOpened = new Runnable() {
            @Override
            public void run() {
                noteOpened(app, openedAt >= askedAt);
            }
        };
        MAIN.postDelayed(checkOpened, CHECK_OPENED_MS);
    }

    /** Android drops a refused start without a word; this keeps count, so Settings can say what to allow. */
    private static void noteOpened(Context c, boolean ok) {
        SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        int n = p.getInt(PREF_BLOCKED, 0);
        if (ok) {
            if (n != 0) p.edit().putInt(PREF_BLOCKED, 0).apply();
        } else {
            p.edit().putInt(PREF_BLOCKED, n + 1).putLong(PREF_BLOCKED_AT, System.currentTimeMillis()).apply();
        }
    }

    /** Main thread: the song or its state changed. */
    static void refresh() {
        if (shown != null) shown.bind();
    }

    static void close() {
        if (shown != null) shown.finish();
    }

    // ───────────────────────── lifecycle ─────────────────────────
    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        openedAt = SystemClock.elapsedRealtime(); // Android let it open (see show)
        JSONObject s = MusicService.now();
        if (s == null || !s.optBoolean("hasTrack")) {
            finish();
            return;
        }
        shown = this;
        dn = getResources().getDisplayMetrics().density;
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);
        else w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        View page = buildWeb();
        setContentView(page != null ? page : build());
        if (page != null) MAIN.postDelayed(pageTimeout, PAGE_READY_MS);
        IntentFilter f = new IntentFilter(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screen, f);
        listening = true;
        bind();
    }

    /** Asked for again while open: back to the front, as if new. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openedAt = SystemClock.elapsedRealtime();
        if (!intent.getBooleanExtra(EXTRA_BACK, false)) { // the screen went off again: a new round
            lockedSeenAt = 0;
            broughtBack = 0;
        }
        MAIN.removeCallbacks(unstick);
        if (!left) {
            leaving = false;
            openAfterUnlock = false;
            settle();
            resetPage();
        }
        bind();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (closeIfUnlocked()) return;
        watch();
        bind();
        MAIN.removeCallbacks(tick);
        if (web == null) MAIN.post(tick);
    }

    @Override
    protected void onPause() {
        MAIN.removeCallbacks(tick);
        MAIN.removeCallbacks(watchUnlock);
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Out of sight on an unlocked phone (a call came in, Home, an app opened from the notification shade):
        // done. Left open, it would only sit behind everything.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (isChangingConfigurations() || isFinishing() || left || pm == null || !pm.isInteractive() || locked()) return;
        // "Stay until I swipe it away": some phones put the home screen (or the last app) over it as they unlock.
        // Covered within moments of the lock screen going: that's the phone, so come back to the front.
        if (!leaving && stays(this) && broughtBack < 2 && lockedSeenAt > 0
                && SystemClock.elapsedRealtime() - lockedSeenAt < JUST_UNLOCKED_MS) {
            broughtBack++;
            Intent i = new Intent(this, LockScreenActivity.class).putExtra(EXTRA_BACK, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                            | Intent.FLAG_ACTIVITY_NO_USER_ACTION | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try {
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        MAIN.removeCallbacks(tick);
        MAIN.removeCallbacks(checkLocked);
        MAIN.removeCallbacks(unstick);
        MAIN.removeCallbacks(pageTimeout);
        MAIN.removeCallbacks(watchUnlock);
        if (shown == this) shown = null;
        if (web != null) {
            web.destroy();
            web = null;
        }
        if (listening) {
            listening = false;
            try {
                unregisterReceiver(screen);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        finish(); // shows Android's own lock screen underneath
    }

    /** Whether Android's lock screen is up, even while this covers it. */
    private boolean locked() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return km != null && (km.isKeyguardLocked() || km.isDeviceLocked());
    }

    /** The screen is on and there's no lock screen to cover (no screen lock, or it hadn't locked yet): step aside. */
    private boolean closeIfUnlocked() {
        if (leaving || stays(this)) return false;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isInteractive() && !locked()) {
            closeNow();
            return true;
        }
        return false;
    }

    /** Checks for the unlock every WATCH_MS while the screen is on and it covers the lock screen. */
    private void watch() {
        MAIN.removeCallbacks(watchUnlock);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!leaving && !left && pm != null && pm.isInteractive()) MAIN.post(watchUnlock);
    }

    /** The phone unlocked without the swipe up (face, fingerprint, PIN on Android's own lock screen). */
    private void unlocked() {
        MAIN.removeCallbacks(watchUnlock);
        if (leaving || left) return;
        if (lockedSeenAt == 0) lockedSeenAt = SystemClock.elapsedRealtime(); // Android's message came first
        if (!stays(this)) closeNow(); // straight to the home screen; with Stay on, it waits for the swipe
    }

    /** Gone at once, with no fade, so it isn't seen over the home screen. */
    @SuppressWarnings("deprecation")
    private void closeNow() {
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        finish();
        overridePendingTransition(0, 0);
    }

    /** Asks Android to unlock (it shows the PIN or pattern if there is one), then closes; optionally opens B-Side. */
    private void unlock(final boolean openApp) {
        if (leaving) return;
        leaving = true;
        openAfterUnlock = openApp;
        MAIN.removeCallbacks(unstick);
        MAIN.postDelayed(unstick, UNSTICK_MS);
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    leave(openApp);
                }

                @Override
                public void onDismissCancelled() {
                    if (left) return;
                    MAIN.removeCallbacks(unstick);
                    leaving = false;
                    settle();
                    resetPage();
                }

                @Override
                public void onDismissError() {
                    leave(openApp);
                }
            });
        } else {
            leave(openApp);
        }
    }

    @SuppressWarnings("deprecation")
    private void leave(boolean openApp) {
        if (left) return; // both the unlock's answer and "unlocked" can arrive
        left = true;
        MAIN.removeCallbacks(unstick);
        if (openApp) {
            Intent i = new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .putExtra(MusicService.EXTRA_OPEN_PLAYER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                startActivity(i);
            } catch (Exception ignored) {
            }
        }
        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    // ───────────────────────── showing the song ─────────────────────────
    private void bind() {
        JSONObject s = MusicService.now();
        if (s == null || !s.optBoolean("hasTrack")) {
            finish();
            return;
        }
        if (web != null) {
            if (webReady) web.evaluateJavascript("window.bsideLockUpdate&&bsideLockUpdate()", null);
            return;
        }
        if (content == null) return;
        String t = s.optString("title"), a = s.optString("artist"), al = s.optString("album");
        if (!t.equals(String.valueOf(title.getText()))) {
            title.setText(t);
            title.setSelected(true); // scrolls a long title
        }
        sub.setText(al.isEmpty() || al.equalsIgnoreCase(a) ? a : (a.isEmpty() ? al : a + " · " + al));
        boolean playing = s.optBoolean("playing");
        play.setImageResource(playing ? R.drawable.ic_n_pause : R.drawable.ic_n_play);
        play.setContentDescription(getString(playing ? R.string.pause : R.string.play));
        int accent = brighten(colourOf(s));
        Bitmap cover = MusicService.coverNow();
        String key = s.optString("artKey", "") + "|" + (cover == null ? 0 : cover.getWidth());
        if (cover != shownCover || accent != shownColor || !key.equals(shownKey)) {
            shownCover = cover;
            shownColor = accent;
            shownKey = key;
            showCover(cover, accent);
            ColorStateList acc = ColorStateList.valueOf(accent);
            seek.setProgressTintList(acc);
            seek.setThumbTintList(acc);
        }
        updateTime();
    }

    private void showCover(Bitmap cover, int accent) {
        if (cover != null) {
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setPadding(0, 0, 0, 0);
            art.setBackground(null);
            art.setImageBitmap(cover);
            art.setImageTintList(null);
            BitmapDrawable soft = new BitmapDrawable(getResources(), blurred(cover));
            soft.setFilterBitmap(true);
            bg.setImageDrawable(soft);
        } else {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{accent, mix(accent, 0xFF000000, .55f)});
            art.setBackground(g);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = Math.round(art.getLayoutParams().width * .3f);
            art.setPadding(pad, pad, pad, pad);
            art.setImageResource(R.drawable.ic_stat_note);
            art.setImageTintList(ColorStateList.valueOf(0xE6FFFFFF));
            bg.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{mix(accent, 0xFF000000, .35f), 0xFF050507}));
        }
    }

    private void updateTime() {
        JSONObject s = MusicService.now();
        if (s == null || seek == null) return;
        long dur = s.optLong("duration"), pos = MusicService.positionNow();
        if (!seeking) {
            seek.setProgress(dur > 0 ? (int) (pos * 1000 / dur) : 0);
            tCur.setText(fmt(pos));
        }
        tDur.setText(fmt(dur));
    }

    // ───────────────────────── the lock screen page ─────────────────────────
    private View buildWeb() {
        final WebView w;
        try {
            w = new WebView(this);
        } catch (Throwable e) {
            return null; // no WebView right now (it may be updating): the native screen
        }
        web = w;
        w.setBackgroundColor(Color.BLACK);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.addJavascriptInterface(new Bridge(), "BSideLock");
        w.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                insetTop = in.getSystemWindowInsetTop();
                insetBottom = in.getSystemWindowInsetBottom();
                pushInsets();
                return in.consumeSystemWindowInsets();
            }
        });
        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String path = u.getPath() == null ? "" : u.getPath();
                try {
                    if (LOCK_HOST.equals(u.getHost())) {
                        if (path.equals("/") || path.equals("/index.html")) {
                            return new WebResourceResponse("text/html", "utf-8", WebUpdater.peek(getApplicationContext()));
                        }
                        if (path.startsWith("/__cover/")) {
                            byte[] b = MusicService.coverBytes(Uri.decode(path.substring(9)));
                            if (b != null) return new WebResourceResponse("image/jpeg", null, new ByteArrayInputStream(b));
                        }
                    }
                } catch (Exception ignored) {
                }
                return empty(); // nothing else loads on the lock screen: no network at all
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (view != web) return;
                webReady = true;
                pushInsets();
                bind();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                if (view == web) useNative(); // its renderer went away: carry on with the native screen
                else view.destroy();
                return true;
            }
        });
        w.loadUrl("https://" + LOCK_HOST + "/index.html#lock");
        return w;
    }

    /** From the page to the native screen, keeping whatever state the unlock is in. */
    private void useNative() {
        WebView w = web;
        if (w == null) return;
        web = null;
        webReady = webDrawn = false;
        MAIN.removeCallbacks(pageTimeout);
        setContentView(build());
        w.destroy();
        bind();
        MAIN.removeCallbacks(tick);
        MAIN.post(tick);
    }

    private void resetPage() {
        if (web != null && webReady) web.evaluateJavascript("window.bsideLockReset&&bsideLockReset()", null);
    }

    private static WebResourceResponse empty() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    private void pushInsets() {
        if (web == null || !webReady) return;
        web.evaluateJavascript("window.bsideInsets&&bsideInsets(" + (insetTop / dn) + "," + (insetBottom / dn) + ")", null);
    }

    /** What the lock screen page may ask for and do: the song, its look, and playback controls. */
    private final class Bridge {
        @JavascriptInterface
        public String state() {
            JSONObject s = MusicService.now();
            if (s == null) return "{}";
            try {
                JSONObject o = new JSONObject(s.toString());
                o.put("position", MusicService.positionNow());
                String key = s.optString("artKey", "");
                byte[] b = MusicService.coverBytes(key);
                o.put("cover", b != null ? "/__cover/" + Uri.encode(key) + "?n=" + b.length : "");
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String config() {
            return look(LockScreenActivity.this);
        }

        /** The page has drawn the song: it's what shows from now on. */
        @JavascriptInterface
        public void ready() {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    webDrawn = true;
                    MAIN.removeCallbacks(pageTimeout);
                }
            });
        }

        @JavascriptInterface
        public String lyrics() {
            return MusicService.lyrics();
        }

        @JavascriptInterface
        public boolean clock24() {
            return DateFormat.is24HourFormat(LockScreenActivity.this);
        }

        @JavascriptInterface
        public String insets() {
            return "{\"top\":" + (insetTop / dn) + ",\"bottom\":" + (insetBottom / dn) + "}";
        }

        @JavascriptInterface
        public void command(final String c) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    if ("pause".equals(c)) WebHost.remote("pause", 0);
                    else if ("fav".equals(c)) WebHost.remote("fav", 0);
                    else if ("play".equals(c) || "next".equals(c) || "prev".equals(c)) WebHost.command(LockScreenActivity.this, c);
                }
            });
        }

        @JavascriptInterface
        public void seek(long ms) {
            WebHost.remote("seek", Math.max(0, ms));
        }

        @JavascriptInterface
        public void unlock(final boolean openApp) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    LockScreenActivity.this.unlock(openApp);
                }
            });
        }
    }

    // ───────────────────────── layout (when there's no WebView) ─────────────────────────
    private View build() {
        final SwipeFrame root = new SwipeFrame(this);
        root.setBackgroundColor(0xFF050507);
        bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x8C000000, 0x66000000, 0xD9000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        boolean wide = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        int side = wide ? (int) Math.min(sh * .6f, sw * .36f) : (int) Math.min(sw - dp(72), sh * .38f);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        text(time, wide ? 54 : 76, Color.WHITE, Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        TextClock date = new TextClock(this);
        String datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
        date.setFormat12Hour(datePattern);
        date.setFormat24Hour(datePattern);
        text(date, 16, 0xD9FFFFFF, Typeface.create("sans-serif-medium", Typeface.NORMAL));

        art = new ImageView(this);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(22));
            }
        });
        art.setClipToOutline(true);
        art.setElevation(dp(18));
        art.setContentDescription(getString(R.string.lock_open));
        art.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                unlock(true);
            }
        });

        title = new TextView(this);
        text(title, 22, Color.WHITE, Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setHorizontalFadingEdgeEnabled(true);
        title.setGravity(wide ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        sub = new TextView(this);
        text(sub, 15, 0xB3FFFFFF, null);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setGravity(wide ? Gravity.START : Gravity.CENTER_HORIZONTAL);

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x4DFFFFFF));
        seek.setPadding(dp(4), dp(10), dp(4), dp(10));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                if (!user) return;
                JSONObject s = MusicService.now();
                if (s != null) tCur.setText(fmt(s.optLong("duration") * p / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
                seeking = false;
                JSONObject s = MusicService.now();
                if (s != null && s.optLong("duration") > 0) WebHost.remote("seek", s.optLong("duration") * b.getProgress() / 1000);
            }
        });
        LinearLayout times = new LinearLayout(this);
        tCur = new TextView(this);
        tDur = new TextView(this);
        text(tCur, 12, 0xB3FFFFFF, null);
        text(tDur, 12, 0xB3FFFFFF, null);
        tCur.setFontFeatureSettings("tnum");
        tDur.setFontFeatureSettings("tnum");
        times.addView(tCur, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(tDur, new LinearLayout.LayoutParams(-2, -2));
        times.setPadding(dp(6), 0, dp(6), 0);

        LinearLayout ctrls = new LinearLayout(this);
        ctrls.setGravity(Gravity.CENTER);
        ImageButton prev = button(R.drawable.ic_n_prev, R.string.prev, 60, false);
        play = button(R.drawable.ic_n_play, R.string.play, 78, true);
        ImageButton next = button(R.drawable.ic_n_next, R.string.next, 60, false);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WebHost.command(LockScreenActivity.this, "prev");
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WebHost.command(LockScreenActivity.this, "next");
            }
        });
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (MusicService.isPlaying()) WebHost.remote("pause", 0);
                else WebHost.command(LockScreenActivity.this, "play");
            }
        });
        ctrls.addView(prev, new LinearLayout.LayoutParams(dp(60), dp(60)));
        ctrls.addView(play, withMargins(dp(78), dp(26)));
        ctrls.addView(next, new LinearLayout.LayoutParams(dp(60), dp(60)));

        LinearLayout hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.VERTICAL);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView up = new ImageView(this);
        up.setImageResource(R.drawable.ic_k_up);
        up.setImageTintList(ColorStateList.valueOf(0xB3FFFFFF));
        TextView hintText = new TextView(this);
        text(hintText, 13, 0xB3FFFFFF, null);
        hintText.setText(R.string.lock_unlock);
        hint.addView(up, new LinearLayout.LayoutParams(dp(22), dp(22)));
        hint.addView(hintText);
        hint.setPadding(dp(16), dp(8), dp(16), dp(4));
        hint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                unlock(false);
            }
        });

        final LinearLayout box = new LinearLayout(this);
        if (!wide) {
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.addView(time);
            box.addView(date, margins(-2, -2, 0, dp(6), 0, 0));
            box.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
            box.addView(art, new LinearLayout.LayoutParams(side, side));
            box.addView(title, margins(-1, -2, 0, dp(30), 0, 0));
            box.addView(sub, margins(-1, -2, 0, dp(4), 0, 0));
            box.addView(seek, margins(-1, -2, 0, dp(16), 0, 0));
            box.addView(times, new LinearLayout.LayoutParams(-1, -2));
            box.addView(ctrls, margins(-1, -2, 0, dp(12), 0, 0));
            box.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
            box.addView(hint);
            box.setPadding(dp(28), 0, dp(28), 0);
        } else {
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.addView(art, new LinearLayout.LayoutParams(side, side));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_VERTICAL);
            col.addView(time);
            col.addView(date);
            col.addView(title, margins(-1, -2, 0, dp(18), 0, 0));
            col.addView(sub);
            col.addView(seek, margins(-1, -2, 0, dp(8), 0, 0));
            col.addView(times, new LinearLayout.LayoutParams(-1, -2));
            col.addView(ctrls, margins(-1, -2, 0, dp(4), 0, 0));
            col.addView(hint, margins(-1, -2, 0, dp(6), 0, 0));
            box.addView(col, margins(0, -1, dp(40), 0, 0, 0, 1));
            box.setPadding(dp(40), 0, dp(32), 0);
        }
        content = box;
        root.addView(box, new FrameLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                box.setPadding(box.getPaddingLeft(), in.getSystemWindowInsetTop() + dp(36), box.getPaddingRight(), in.getSystemWindowInsetBottom() + dp(18));
                return in.consumeSystemWindowInsets();
            }
        });
        return root;
    }

    private LinearLayout.LayoutParams withMargins(int size, int side) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(side, 0, side, 0);
        return lp;
    }

    private static LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private static LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h, weight);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private ImageButton button(int icon, int label, int sizeDp, boolean filled) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setContentDescription(getString(label));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(filled ? 22 : 14);
        b.setPadding(pad, pad, pad, pad);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(filled ? Color.WHITE : Color.TRANSPARENT);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(filled ? 0x33000000 : 0x40FFFFFF), shape, mask));
        b.setImageTintList(ColorStateList.valueOf(filled ? 0xFF111114 : Color.WHITE));
        if (filled) b.setElevation(dp(6));
        return b;
    }

    private void text(TextView v, float sp, int colour, Typeface face) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(colour);
        if (face != null) v.setTypeface(face);
        v.setShadowLayer(dp(6), 0, dp(1), 0x59000000);
    }

    private int dp(float v) {
        return Math.round(v * dn);
    }

    /** Swipe up anywhere to unlock; the player follows the finger. */
    private final class SwipeFrame extends FrameLayout {
        private final int slop;
        private float x0, y0;
        private boolean dragging;
        private VelocityTracker vt;

        SwipeFrame(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop() * 2;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            track(e);
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                x0 = e.getX();
                y0 = e.getY();
                dragging = false;
            } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && !dragging && !seeking) {
                float dy = e.getY() - y0, dx = e.getX() - x0;
                if (-dy > slop && Math.abs(dy) > Math.abs(dx) * 1.3f) dragging = true;
            }
            return dragging;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            track(e);
            float dy = e.getY() - y0;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getX();
                    y0 = e.getY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && -dy > slop) dragging = true;
                    if (dragging && !leaving) {
                        float off = Math.min(0, dy);
                        content.setTranslationY(off);
                        content.setAlpha(Math.max(.25f, 1 + off / (getHeight() * .55f)));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float vy = 0;
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000);
                        vy = vt.getYVelocity();
                        vt.recycle();
                        vt = null;
                    }
                    if (dragging && e.getActionMasked() == MotionEvent.ACTION_UP && (-dy > getHeight() * .16f || vy < -dn * 900)) {
                        content.animate().translationY(-getHeight() * .35f).alpha(0).setDuration(180).setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator a) {
                                content.animate().setListener(null);
                                unlock(false);
                            }
                        }).start();
                    } else if (dragging) {
                        settle();
                    }
                    dragging = false;
                    return true;
            }
            return super.onTouchEvent(e);
        }

        private void track(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && vt != null) {
                vt.recycle();
                vt = null;
            }
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
        }
    }

    private void settle() {
        if (content != null) content.animate().translationY(0).alpha(1).setDuration(220).setListener(null).start();
    }

    // ───────────────────────── small helpers ─────────────────────────
    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000), h = s / 3600, m = s % 3600 / 60, x = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, x) : String.format(Locale.ROOT, "%d:%02d", m, x);
    }

    private static int colourOf(JSONObject s) {
        try {
            return Color.parseColor(s.optString("color", "#2F6BFF").trim()) | 0xFF000000;
        } catch (Exception e) {
            return 0xFF2F6BFF;
        }
    }

    /** The theme colour, lifted until it reads well on the dark lock screen. */
    private static int brighten(int c) {
        for (int i = 0; i < 6 && lum(c) < .42f; i++) c = mix(c, 0xFFFFFFFF, .22f);
        return c;
    }

    private static float lum(int c) {
        return (.2126f * Color.red(c) + .7152f * Color.green(c) + .0722f * Color.blue(c)) / 255f;
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** A soft, blurred copy of the cover for the background: shrunk, box-blurred twice, then stretched. */
    private static Bitmap blurred(Bitmap src) {
        int n = 36;
        Bitmap small = Bitmap.createScaledBitmap(src, n, n, true);
        int[] px = new int[n * n], tmp = new int[n * n];
        small.getPixels(px, 0, n, 0, 0, n, n);
        for (int pass = 0; pass < 2; pass++) {
            box(px, tmp, n, true);
            box(tmp, px, n, false);
        }
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, n, 0, 0, n, n);
        return out;
    }

    private static void box(int[] in, int[] out, int n, boolean horizontal) {
        int r = 3;
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                int sr = 0, sg = 0, sb = 0, k = 0;
                for (int d = -r; d <= r; d++) {
                    int q = Math.min(n - 1, Math.max(0, b + d));
                    int c = horizontal ? in[a * n + q] : in[q * n + a];
                    sr += (c >> 16) & 255;
                    sg += (c >> 8) & 255;
                    sb += c & 255;
                    k++;
                }
                int v = 0xFF000000 | ((sr / k) << 16) | ((sg / k) << 8) | (sb / k);
                if (horizontal) out[a * n + b] = v;
                else out[b * n + a] = v;
            }
        }
    }
}
