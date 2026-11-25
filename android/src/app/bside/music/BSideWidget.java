package app.bside.music;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.RemoteViews;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The home-screen widget: the song playing, its cover and previous / play / next, in the style chosen in B-Side.
 * The page draws two pictures of it with the style's own shapes and fonts (one playing, one paused, see
 * widgetDraw in app.html) and hands them over with setWidget; this shows the one that matches what's playing,
 * which stays right after B-Side closes. The buttons sit over the picture where the page drew them. Until a
 * picture arrives (a widget added before B-Side ever ran), a plain fallback with Android's own icons shows.
 */
public class BSideWidget extends AppWidgetProvider {
    static final String A_TOGGLE = "app.bside.music.W_TOGGLE";
    static final String A_NEXT = "app.bside.music.W_NEXT";
    static final String A_PREV = "app.bside.music.W_PREV";
    private static Boolean shownPlaying;

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        push(c, MusicService.isPlaying());
        WebHost.redrawWidget();
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
        push(c, MusicService.isPlaying());
        WebHost.redrawWidget(); // a new size: the page draws a new picture for it
    }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        String a = i == null ? null : i.getAction();
        if (A_TOGGLE.equals(a)) {
            if (MusicService.isPlaying() && WebHost.peek() != null) WebHost.remote("pause", 0);
            else MusicService.resume(c, "play");
        } else if (A_NEXT.equals(a)) MusicService.resume(c, "next");
        else if (A_PREV.equals(a)) MusicService.resume(c, "prev");
    }

    static int count(Context c) {
        try {
            return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, BSideWidget.class)).length;
        } catch (Exception e) {
            return 0;
        }
    }

    /** {"count": widgets on the home screens, "w", "h": the biggest one's size in dp, "density"} */
    static String info(Context c) {
        int n = 0, w = 0, h = 0;
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            for (int id : m.getAppWidgetIds(new ComponentName(c, BSideWidget.class))) {
                n++;
                Bundle o = m.getAppWidgetOptions(id);
                int ww = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), hh = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
                if (ww > w) w = ww;
                if (hh > h) h = hh;
            }
        } catch (Exception ignored) {
        }
        if (w <= 0) w = 320;
        if (h <= 0) h = 80;
        return "{\"count\":" + n + ",\"w\":" + w + ",\"h\":" + h + ",\"density\":" + c.getResources().getDisplayMetrics().density + "}";
    }

    /** The page's two pictures (PNG, base64): playing and paused. */
    static void save(Context c, String playing, String paused) {
        try {
            write(new File(c.getFilesDir(), "widget-playing.png"), Base64.decode(playing, Base64.DEFAULT));
            write(new File(c.getFilesDir(), "widget-paused.png"), Base64.decode(paused, Base64.DEFAULT));
        } catch (Exception ignored) {
            return;
        }
        shownPlaying = null;
        push(c, MusicService.isPlaying());
    }

    private static void write(File f, byte[] b) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(b);
        }
        if (!tmp.renameTo(f)) throw new Exception("rename");
    }

    /** Shows the playing or paused picture; skipped when nothing changed. */
    static void pushIfChanged(Context c, boolean playing) {
        if (shownPlaying != null && shownPlaying == playing) return;
        push(c, playing);
    }

    static void push(Context c, boolean playing) {
        if (count(c) == 0) return;
        shownPlaying = playing;
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        Bitmap b = null;
        try {
            File f = new File(c.getFilesDir(), playing ? "widget-playing.png" : "widget-paused.png");
            if (f.isFile()) b = BitmapFactory.decodeFile(f.getPath());
        } catch (Exception ignored) {
        }
        if (b != null) {
            v.setImageViewBitmap(R.id.w_bg, b);
            v.setViewVisibility(R.id.w_fallback, View.GONE);
            v.setImageViewResource(R.id.w_prev, android.R.color.transparent);
            v.setImageViewResource(R.id.w_play, android.R.color.transparent);
            v.setImageViewResource(R.id.w_next, android.R.color.transparent);
        } else {
            v.setImageViewResource(R.id.w_bg, R.drawable.widget_bg);
            v.setViewVisibility(R.id.w_fallback, View.VISIBLE);
            v.setImageViewResource(R.id.w_prev, R.drawable.ic_n_prev);
            v.setImageViewResource(R.id.w_play, playing ? R.drawable.ic_n_pause : R.drawable.ic_n_play);
            v.setImageViewResource(R.id.w_next, R.drawable.ic_n_next);
        }
        v.setContentDescription(R.id.w_play, c.getString(playing ? R.string.pause : R.string.play));
        v.setOnClickPendingIntent(R.id.w_open, open(c));
        v.setOnClickPendingIntent(R.id.w_prev, button(c, A_PREV));
        // Nothing to play yet (B-Side was never used, or its queue was cleared): Play opens B-Side instead.
        boolean nothing = !playing && WebHost.peek() == null && MusicService.lastSong(c) == null;
        v.setOnClickPendingIntent(R.id.w_play, nothing ? open(c) : button(c, A_TOGGLE));
        v.setOnClickPendingIntent(R.id.w_next, button(c, A_NEXT));
        try {
            AppWidgetManager.getInstance(c).updateAppWidget(new ComponentName(c, BSideWidget.class), v);
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent button(Context c, String a) {
        Intent i = new Intent(c, BSideWidget.class).setAction(a);
        return PendingIntent.getBroadcast(c, a.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent open(Context c) {
        Intent i = new Intent(c, MainActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .putExtra(MusicService.EXTRA_OPEN_PLAYER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
