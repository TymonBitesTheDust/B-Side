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

import org.json.JSONObject;

import java.util.Locale;

/**
 * The lock screen player (Settings → Lock screen player). When the screen goes off while music plays (or a few
 * minutes after it was paused), MusicService puts this screen over Android's lock screen: the time, the cover, the
 * song and big controls. It only ever shows
 * playback controls; swiping up (or tapping the cover) asks Android to unlock as usual. It closes by itself once
 * the phone is unlocked, or when there's nothing playing.
 *
 * Android 10 and later only let an app open a screen from the background with "Display over other apps", so the
 * setting asks for that permission.
 */
public class LockScreenActivity extends Activity {
    private static final String PREF = "lockPlayer";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static LockScreenActivity shown;

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
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_USER_PRESENT.equals(i.getAction())) {
                if (!leaving) finish(); // unlocked by fingerprint, face or PIN (a swipe up closes it itself)
            }
            else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) MAIN.postDelayed(checkLocked, 450);
        }
    };

    private float dn;
    private View content;
    private ImageView bg, art;
    private TextView title, sub, tCur, tDur;
    private SeekBar seek;
    private ImageButton play;
    private boolean seeking, leaving, listening;
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

    /** Whether Android lets B-Side put this screen up from the background. */
    static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(c);
    }

    static void show(Context c) {
        if (!enabled(c)) return;
        if (shown != null && !shown.isFinishing()) {
            shown.bind();
            return;
        }
        Intent i = new Intent(c, LockScreenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        try {
            c.startActivity(i);
        } catch (Exception ignored) {
            // Not allowed from the background right now: Android's own lock screen controls are still there.
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
        setContentView(build());
        IntentFilter f = new IntentFilter(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screen, f);
        listening = true;
        bind();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (closeIfUnlocked()) return;
        bind();
        MAIN.removeCallbacks(tick);
        MAIN.post(tick);
    }

    @Override
    protected void onPause() {
        MAIN.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        MAIN.removeCallbacks(tick);
        MAIN.removeCallbacks(checkLocked);
        if (shown == this) shown = null;
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

    /** The screen is on and there's no lock screen to cover (no screen lock, or it hadn't locked yet): step aside. */
    private boolean closeIfUnlocked() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (pm != null && pm.isInteractive() && km != null && !km.isKeyguardLocked()) {
            finish();
            return true;
        }
        return false;
    }

    /** Asks Android to unlock (it shows the PIN or pattern if there is one), then closes; optionally opens B-Side. */
    private void unlock(final boolean openApp) {
        if (leaving) return;
        leaving = true;
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    leave(openApp);
                }

                @Override
                public void onDismissCancelled() {
                    leaving = false;
                    settle();
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
        if (content == null) return;
        JSONObject s = MusicService.now();
        if (s == null || !s.optBoolean("hasTrack")) {
            finish();
            return;
        }
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

    // ───────────────────────── layout ─────────────────────────
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
