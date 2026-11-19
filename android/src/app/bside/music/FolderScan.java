package app.bside.music;

import android.content.ContentResolver;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads folders picked with Android's folder picker: the songs in them, and the cover pictures and .lrc lyrics
 * beside them. Used when a folder is added, and again when B-Side checks it for new and deleted songs.
 */
final class FolderScan {
    static final Pattern AUDIO_NAME = Pattern.compile(".*\\.(mp3|m4a|m4b|mp4|aac|flac|ogg|oga|opus|wav|wave|wma|webm|weba|mka|aif|aiff|amr|3gp)$");
    static final Pattern SIDE_NAME = Pattern.compile("(.*\\.lrc)|((cover|folder|front|album[^/]*)\\.(jpe?g|png|webp))");
    private static final int MAX_DEPTH = 12, MAX_FILES = 20000;

    interface Sink {
        void file(String docId, String name, long size, String path, boolean audio);
    }

    private FolderScan() {
    }

    static String rootName(ContentResolver cr, Uri tree, String rootId) {
        String name = null;
        try (Cursor c = cr.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        }
        return name == null || name.isEmpty() ? "Folder" : name;
    }

    /**
     * Lists a folder and its subfolders. Returns false when part of it couldn't be read (or it was too big to read
     * whole), so a caller never takes an unreadable folder for one whose songs were deleted.
     */
    static boolean walk(ContentResolver cr, Uri tree, String docId, String path, Sink out, int depth, int[] count) {
        if (depth > MAX_DEPTH || count[0] > MAX_FILES) return false;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        List<String[]> dirs = new ArrayList<>();
        try (Cursor c = cr.query(children, cols, null, null, null)) {
            if (c == null) return false;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                if (id == null || name == null || name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.add(new String[]{id, name});
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                boolean audio = (mime != null && (mime.startsWith("audio/") || mime.equals("application/ogg") || mime.equals("application/x-flac")))
                        || AUDIO_NAME.matcher(lower).matches();
                if (!audio && !SIDE_NAME.matcher(lower).matches()) continue;
                count[0]++;
                out.file(id, name, size, path, audio);
            }
        } catch (Exception e) {
            return false;
        }
        boolean whole = true;
        for (String[] d : dirs) {
            if (!walk(cr, tree, d[0], path + "/" + d[1], out, depth + 1, count)) whole = false;
        }
        return whole;
    }

    /** The folders and single songs B-Side may still read: {"trees":[uri…],"files":[uri…]}. */
    static String access(ContentResolver cr) {
        JSONArray trees = new JSONArray(), files = new JSONArray();
        try {
            for (UriPermission p : cr.getPersistedUriPermissions()) {
                if (!p.isReadPermission()) continue;
                Uri u = p.getUri();
                if (isTree(u)) trees.put(u.toString());
                else files.put(u.toString());
            }
        } catch (Exception ignored) {
        }
        return "{\"trees\":" + trees + ",\"files\":" + files + "}";
    }

    static boolean isTree(Uri u) {
        try {
            return DocumentsContract.isTreeUri(u);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean persisted(ContentResolver cr, Uri u) {
        try {
            for (UriPermission p : cr.getPersistedUriPermissions()) if (p.isReadPermission() && u.equals(p.getUri())) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Looks at songs that were picked one by one. For each it reports "ok" (with name and size), "none" (the
     * app holding it answered nothing), "empty" (it answered that there's no such file) or "err". The page
     * decides from that, and from the other answers, whether a file is really gone.
     */
    static JSONArray check(ContentResolver cr, JSONArray uris) {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < uris.length(); i++) {
            String s = uris.optString(i, "");
            if (s.isEmpty() || !seen.add(s)) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("u", s);
                Uri u = Uri.parse(s);
                try (Cursor c = cr.query(u, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (c == null) o.put("st", "none");
                    else if (!c.moveToFirst()) o.put("st", "empty");
                    else {
                        o.put("st", "ok");
                        o.put("n", c.isNull(0) ? "" : c.getString(0));
                        o.put("s", c.isNull(1) ? 0 : c.getLong(1));
                    }
                } catch (Exception e) {
                    o.put("st", "err");
                }
                out.put(o);
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
