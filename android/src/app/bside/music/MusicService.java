package app.bside.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONObject;

/**
 * Keeps B-Side alive while music plays and mirrors the player into a media
 * notification / lock-screen controls. Playback itself happens in the WebView;
 * buttons here are forwarded to it through WebHost.remote().
 */
public class MusicService extends Service {
    static final String EXTRA_OPEN_PLAYER = "app.bside.music.OPEN_PLAYER";
    private static final String CHANNEL = "playback";
    private static final int NID = 7;
    private static final String A_UPDATE = "app.bside.music.UPDATE";
    private static final String A_PLAY = "app.bside.music.PLAY";
    private static final String A_PAUSE = "app.bside.music.PAUSE";
    private static final String A_NEXT = "app.bside.music.NEXT";
    private static final String A_PREV = "app.bside.music.PREV";
    private static final String A_STOP = "app.bside.music.STOP";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static MusicService inst;
    private static JSONObject state;
    private static String artKey = "";
    private static Bitmap art;

    private MediaSession session;
    private NotificationManager nm;
    private boolean foreground, noisyRegistered;
    private PowerManager.WakeLock wakeLock;

    /**
     * After a pause the service stays in the foreground this long, so Android treats B-Side like an app in use
     * and doesn't close it while you're between songs. Afterwards the notification can be swiped away as usual.
     */
    private static final long PAUSE_GRACE_MS = 45 * 60 * 1000L;
    private final Runnable dropForeground = new Runnable() {
        @Override
        public void run() {
            if (isPlaying() || !foreground) return;
            stopForeground(STOP_FOREGROUND_DETACH);
            foreground = false;
            if (state != null && nm != null) nm.notify(NID, build(false));
        }
    };

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) WebHost.remote("pause", 0);
        }
    };

    // ───────────────────────── entry points ─────────────────────────
    /** JS → native state update (called on the JavaBridge thread). */
    static void onJsUpdate(final Context ctx, final String json) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                JSONObject s;
                try {
                    s = new JSONObject(json);
                } catch (Exception e) {
                    return;
                }
                state = s;
                String key = s.optString("artKey", "");
                if (s.has("art")) {
                    try {
                        byte[] b = Base64.decode(s.getString("art"), Base64.DEFAULT);
                        Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
                        if (bm != null) {
                            art = bm;
                            artKey = key;
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (!s.optBoolean("playing")) MainActivity.applyPendingIcon(ctx);
                if (!s.optBoolean("hasTrack")) {
                    if (inst != null) inst.shutdown();
                    return;
                }
                if (inst != null) {
                    inst.apply();
                } else if (s.optBoolean("playing")) {
                    Intent i = new Intent(ctx, MusicService.class).setAction(A_UPDATE);
                    try {
                        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
                        else ctx.startService(i);
                    } catch (Exception ignored) {
                        // Not allowed to start from the background right now; the next update will retry.
                    }
                }
            }
        });
    }

    static boolean isPlaying() {
        return state != null && state.optBoolean("playing");
    }

    static void stop(Context ctx) {
        if (inst != null) inst.shutdown();
    }

    // ───────────────────────── service ─────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.channel_desc));
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        session = new MediaSession(this, "B-Side");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setSessionActivity(openAppIntent());
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                WebHost.remote("play", 0);
            }

            @Override
            public void onPause() {
                WebHost.remote("pause", 0);
            }

            @Override
            public void onSkipToNext() {
                WebHost.remote("next", 0);
            }

            @Override
            public void onSkipToPrevious() {
                WebHost.remote("prev", 0);
            }

            @Override
            public void onSeekTo(long pos) {
                WebHost.remote("seek", pos);
            }

            @Override
            public void onStop() {
                WebHost.remote("pause", 0);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (A_UPDATE.equals(a)) {
            if (!foreground && state != null) startFg(build(state.optBoolean("playing")));
            apply();
        } else if (A_PLAY.equals(a)) {
            WebHost.remote("play", 0);
        } else if (A_PAUSE.equals(a)) {
            WebHost.remote("pause", 0);
        } else if (A_NEXT.equals(a)) {
            WebHost.remote("next", 0);
        } else if (A_PREV.equals(a)) {
            WebHost.remote("prev", 0);
        } else if (A_STOP.equals(a)) {
            // The paused notification was dismissed: stop the service and, if the app isn't open, free the player.
            WebHost.remote("pause", 0);
            shutdown();
        }
        if (state == null) shutdown();
        return START_NOT_STICKY;
    }

    /** Swiped out of Recents. Music that is playing keeps playing (like any music app); a paused player is closed. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!isPlaying()) shutdown();
    }

    @Override
    public void onDestroy() {
        MAIN.removeCallbacks(dropForeground);
        releaseWakeLock();
        unregisterNoisy();
        if (nm != null) nm.cancel(NID);
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        if (inst == this) inst = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ───────────────────────── state → session + notification ─────────────────────────
    private void apply() {
        JSONObject s = state;
        if (s == null || session == null) return;
        boolean playing = s.optBoolean("playing");
        Bitmap bm = artFor(s);

        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.optString("title"))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.optString("artist"))
                .putString(MediaMetadata.METADATA_KEY_ALBUM, s.optString("album"))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, s.optLong("duration"));
        if (bm != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bm);
        session.setMetadata(mb.build());

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        s.optLong("position"), playing ? (float) s.optDouble("speed", 1.0) : 0f, SystemClock.elapsedRealtime())
                .build());
        if (!session.isActive()) session.setActive(true);

        Notification n = build(playing);
        if (playing) {
            MAIN.removeCallbacks(dropForeground);
            registerNoisy();
            holdWakeLock();
            startFg(n);
        } else {
            releaseWakeLock();
            unregisterNoisy();
            nm.notify(NID, n); // stays in the foreground for a while; see PAUSE_GRACE_MS
            MAIN.removeCallbacks(dropForeground);
            if (foreground) MAIN.postDelayed(dropForeground, PAUSE_GRACE_MS);
        }
    }

    private static Bitmap artFor(JSONObject s) {
        String key = s.optString("artKey", "");
        return key.length() > 0 && key.equals(artKey) ? art : null;
    }

    private void startFg(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NID, n);
            foreground = true;
        } catch (Exception e) {
            nm.notify(NID, n);
        }
    }

    private void shutdown() {
        MAIN.removeCallbacks(dropForeground);
        releaseWakeLock();
        unregisterNoisy();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        if (nm != null) nm.cancel(NID);
        if (session != null) session.setActive(false);
        stopSelf();
        MAIN.post(new Runnable() {
            @Override
            public void run() {
            WebHost h = WebHost.peek();
            if (h != null) h.destroyIfIdle();
        }
        });
    }

    /** Keeps the CPU awake while playing so the next song starts on time with the screen off. */
    private void holdWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bside:playback");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(3 * 60 * 60 * 1000L); // renewed on every state update; released on pause
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private Notification build(boolean playing) {
        JSONObject s = state != null ? state : new JSONObject();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_note)
                .setContentTitle(s.optString("title", getString(R.string.app_name)))
                .setContentText(s.optString("artist"))
                .setContentIntent(openAppIntent())
                .setDeleteIntent(serviceIntent(A_STOP))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setColor(colourOf(s))
                .addAction(action(R.drawable.ic_n_prev, getString(R.string.prev), A_PREV))
                .addAction(playing ? action(R.drawable.ic_n_pause, getString(R.string.pause), A_PAUSE)
                        : action(R.drawable.ic_n_play, getString(R.string.play), A_PLAY))
                .addAction(action(R.drawable.ic_n_next, getString(R.string.next), A_NEXT))
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        Bitmap bm = artFor(s);
        if (bm != null) b.setLargeIcon(bm);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private static int colourOf(JSONObject s) {
        try {
            return android.graphics.Color.parseColor(s.optString("color", "#2F6BFF").trim()) | 0xFF000000;
        } catch (Exception e) {
            return 0xFF2F6BFF;
        }
    }

    private Notification.Action action(int icon, String title, String act) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), title, serviceIntent(act)).build();
    }

    private PendingIntent serviceIntent(String act) {
        Intent i = new Intent(this, MusicService.class).setAction(act);
        return PendingIntent.getService(this, act.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .putExtra(EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void registerNoisy() {
        if (noisyRegistered) return;
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(noisy, f);
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try {
            unregisterReceiver(noisy);
        } catch (Exception ignored) {
        }
        noisyRegistered = false;
    }
}
