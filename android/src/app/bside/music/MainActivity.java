package app.bside.music;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The B-Side screen. It shows the player that WebHost keeps alive and adds what a web page
 * can't do alone: the system file and folder pickers, "Open with" from other apps,
 * edge-to-edge drawing and the home-screen icon switch. Closing it never stops the music.
 */
public class MainActivity extends Activity {
    private static final int REQ_FILES = 11, REQ_TREE = 12;
    private static final Pattern AUDIO_NAME = Pattern.compile(".*\\.(mp3|m4a|m4b|mp4|aac|flac|ogg|oga|opus|wav|wave|wma|webm|weba|mka|aif|aiff|amr|3gp)$");
    private static final Pattern SIDE_NAME = Pattern.compile("(.*\\.lrc)|((cover|folder|front|album[^/]*)\\.(jpe?g|png|webp))");

    static volatile boolean visible;

    /** Home-screen icon variants; each is an <activity-alias> named .Launcher_<id> in the manifest. */
    static final String[] ICONS = {"bside_cobalt", "bside_pine", "bside_teal", "bside_indigo", "bside_iris",
            "bside_plum", "bside_rose", "bside_coral", "bside_tangerine", "bside_moss", "bside_graphite",
            "archive", "riot", "tide", "frontier", "neon"};
    static final String DEFAULT_ICON = "bside_cobalt";
    static final String PREFS = "bside", PENDING_ICON = "pendingIcon";

    private WebHost host;
    private boolean dark;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Build.VERSION.SDK_INT >= 26 ? Color.TRANSPARENT : 0xFF000000);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        host = WebHost.get(this);
        dark = host.systemDark();
        show();
        handleIntent(getIntent());
    }

    /** Puts the shared player on screen (it may already be mid-song). */
    private void show() {
        WebView web = host.attach(this);
        setContentView(web);
        if (host.bgColor != 0) getWindow().getDecorView().setBackgroundColor(host.bgColor);
        applySystemUi(host.darkUi);
        web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                host.setInsets(insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetBottom());
                return insets.consumeSystemWindowInsets();
            }
        });
        web.requestApplyInsets();
    }

    /** Called by WebHost after the page's renderer had to be rebuilt. */
    void reattach() {
        show();
    }

    @SuppressWarnings("deprecation")
    void applySystemUi(boolean darkUi) {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!darkUi) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if (host.web != null && host.bgColor == 0) host.web.setBackgroundColor(darkUi ? 0xFF030304 : 0xFFF3F3F6);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        boolean d = host.systemDark();
        if (d != dark) {
            dark = d;
            host.onSystemDark(d);
        }
    }

    // ───────────────────────── pickers ─────────────────────────
    boolean launchPicker(String mode, WebChromeClient.FileChooserParams params) {
        try {
            if ("folder".equals(mode)) {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
            } else {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "application/ogg", "application/x-flac"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, REQ_FILES);
            }
            return true;
        } catch (ActivityNotFoundException e) {
            try {
                Intent i = params.createIntent();
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, REQ_FILES);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILES) {
            Uri[] out = null;
            if (resultCode == RESULT_OK && data != null) {
                List<Uri> list = new ArrayList<>();
                ClipData cd = data.getClipData();
                if (cd != null) {
                    for (int k = 0; k < cd.getItemCount(); k++) list.add(cd.getItemAt(k).getUri());
                } else if (data.getData() != null) {
                    list.add(data.getData());
                }
                for (int k = 0; k < list.size() && k < 300; k++) keepAccess(list.get(k));
                out = list.toArray(new Uri[0]);
            }
            host.deliver(out);
        } else if (requestCode == REQ_TREE) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                host.deliver(null);
                return;
            }
            final Uri tree = data.getData();
            keepAccess(tree);
            host.notice("Reading folder…");
            new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Uri> uris = new ArrayList<>();
                final JSONObject paths = new JSONObject();
                try {
                    String rootId = DocumentsContract.getTreeDocumentId(tree);
                    walkTree(tree, rootId, rootName(tree, rootId), uris, paths, 0);
                } catch (Exception ignored) {
                }
                WebHost.MAIN.post(new Runnable() {
            @Override
            public void run() {
                    if (uris.isEmpty()) {
                        host.notice("No songs found in that folder");
                        host.deliver(null);
                        return;
                    }
                    if (host.web != null) host.web.evaluateJavascript("window.__bsidePaths=" + paths + ";", null);
                    host.deliver(uris.toArray(new Uri[0]));
                }
        });
            }
        }, "folder-scan").start();
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * Keeps read access to picked songs after this screen closes, so music that carries on in the
     * background can still open the next file. Best effort: not every picker offers lasting access.
     */
    private void keepAccess(Uri u) {
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    private String rootName(Uri tree, String rootId) {
        String name = null;
        try (Cursor c = getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        }
        return name == null || name.isEmpty() ? "Folder" : name;
    }

    private void walkTree(Uri tree, String docId, String path, List<Uri> out, JSONObject paths, int depth) {
        if (depth > 12 || out.size() > 20000) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        List<String[]> dirs = new ArrayList<>();
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                if (name == null || name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.add(new String[]{id, name});
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                boolean audio = (mime != null && (mime.startsWith("audio/") || mime.equals("application/ogg") || mime.equals("application/x-flac")))
                        || AUDIO_NAME.matcher(lower).matches();
                boolean side = SIDE_NAME.matcher(lower).matches();
                if (!audio && !side) continue;
                out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                try {
                    paths.put(name + "|" + size, path);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        for (String[] d : dirs) walkTree(tree, d[0], path + "/" + d[1], out, paths, depth + 1);
    }

    // ───────────────────────── "Open with" and notification taps ─────────────────────────
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent i) {
        if (i == null) return;
        if (i.getBooleanExtra(MusicService.EXTRA_OPEN_PLAYER, false)) host.openPlayerWhenReady();
        String act = i.getAction();
        List<Uri> uris = new ArrayList<>();
        try {
            if (Intent.ACTION_VIEW.equals(act) && i.getData() != null) {
                uris.add(i.getData());
            } else if (Intent.ACTION_SEND.equals(act)) {
                Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) uris.add(u);
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(act)) {
                ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (l != null) uris.addAll(l);
            }
        } catch (Exception ignored) {
        }
        for (Uri u : uris) host.queueOpen(u, displayName(u), getContentResolver().getType(u));
        // Consume the intent so a later recreation doesn't re-import.
        i.setAction(Intent.ACTION_MAIN);
        i.setData(null);
        i.removeExtra(MusicService.EXTRA_OPEN_PLAYER);
        host.flushPending();
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
        }
        String last = u.getLastPathSegment();
        return last == null ? "Song" : last;
    }

    // ───────────────────────── lifecycle ─────────────────────────
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (host.web == null) {
            super.onBackPressed();
            return;
        }
        host.web.evaluateJavascript("(window.bsideBack&&bsideBack())?'1':'0'", new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
            if (!"\"1\"".equals(v)) moveTaskToBack(true); // like Home: the app, and the music, keep running
        }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        host.js("window.bsideResume&&bsideResume()"); // e.g. back from the battery settings
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    protected void onStop() {
        visible = false;
        super.onStop();
        applyPendingIcon(this);
    }

    @Override
    protected void onDestroy() {
        host.detach(this);
        // Closed, swiped out of Recents or reclaimed by Android: if music is playing it carries on in the
        // background (MusicService keeps the app alive). Otherwise free the player.
        if (!isChangingConfigurations() && !MusicService.isPlaying()) {
            host.destroyIfIdle();
            MusicService.stop(getApplicationContext());
        }
        super.onDestroy();
    }

    // ───────────────────────── launcher icon ─────────────────────────
    static boolean isIcon(String id) {
        for (String s : ICONS) if (s.equals(id)) return true;
        return false;
    }

    private static ComponentName alias(Context c, String id) {
        return new ComponentName(c.getPackageName(), "app.bside.music.Launcher_" + id);
    }

    static String currentIcon(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String id : ICONS) {
            if (pm.getComponentEnabledSetting(alias(c, id)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return id;
        }
        return DEFAULT_ICON; // the manifest enables the default alias until something else is chosen
    }

    /**
     * Swaps the enabled launcher alias. Done only while the app is in the background and not playing:
     * some launchers restart or drop an app whose launcher entry changes while it is in use.
     */
    static void applyPendingIcon(Context ctx) {
        Context c = ctx.getApplicationContext();
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        String want = p.getString(PENDING_ICON, null);
        if (want == null || visible || MusicService.isPlaying()) return;
        p.edit().remove(PENDING_ICON).apply();
        if (!isIcon(want) || want.equals(currentIcon(c))) return;
        PackageManager pm = c.getPackageManager();
        try {
            pm.setComponentEnabledSetting(alias(c, want), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            for (String id : ICONS) {
                if (!id.equals(want)) pm.setComponentEnabledSetting(alias(c, id), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
        } catch (Exception ignored) {
        }
    }
}
