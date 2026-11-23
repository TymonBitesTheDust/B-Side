package app.bside.music;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.Os;

import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * The tag editor's Android side: writes a song's new tags into the file itself, so every app sees them.
 * The page builds the new start of the file (ID3 or FLAC tags, see id3Rewrite and flacRewrite in app.html)
 * and checks that it reads back; this only swaps that start in. The music after it is never changed.
 */
final class TagWriter {
    private TagWriter() {
    }

    /** Whether B-Side was given lasting write access to this song or folder. */
    static boolean canWrite(Context c, Uri u) {
        try {
            for (UriPermission p : c.getContentResolver().getPersistedUriPermissions())
                if (p.isWritePermission() && u.equals(p.getUri())) return true;
            return c.checkUriPermission(u, android.os.Process.myPid(), android.os.Process.myUid(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * {"uri": the song's own address (picked by itself), or "tree", "path", "name", "size": where it is in a
     * picked folder; "head": the new start of the file, base64; "oldLen": how long the old start was}
     * Answers {"ok", "size": the file's size now, "doc": the song's address in the folder, "err"}.
     */
    static JSONObject write(Context c, JSONObject a) {
        JSONObject r = new JSONObject();
        try {
            ContentResolver cr = c.getContentResolver();
            Uri u;
            if (a.optString("uri").length() > 0) u = Uri.parse(a.getString("uri"));
            else {
                Uri tree = Uri.parse(a.getString("tree"));
                String doc = find(cr, tree, a.optString("path"), a.getString("name"), a.optLong("size", -1));
                if (doc == null) return r.put("ok", false).put("err", "The file wasn’t found in its folder");
                u = DocumentsContract.buildDocumentUriUsingTree(tree, doc);
                r.put("doc", u.toString());
            }
            byte[] head = android.util.Base64.decode(a.getString("head"), android.util.Base64.DEFAULT);
            long size = writeHead(c, u, head, a.getLong("oldLen"));
            return r.put("ok", true).put("size", size);
        } catch (SecurityException e) {
            return put(r, "B-Side isn’t allowed to change this file");
        } catch (Exception e) {
            return put(r, e.getMessage() == null ? "The file couldn’t be changed" : e.getMessage());
        }
    }

    private static JSONObject put(JSONObject r, String err) {
        try {
            return r.put("ok", false).put("err", err);
        } catch (Exception e) {
            return r;
        }
    }

    /** A song's document id in a picked folder, from the folder path B-Side shows ("Music/Album") and its name. */
    static String find(ContentResolver cr, Uri tree, String path, String name, long size) {
        String cur = DocumentsContract.getTreeDocumentId(tree);
        String[] segs = path == null ? new String[0] : path.split("/");
        for (int i = 1; i < segs.length; i++) { // segs[0] is the picked folder itself
            if (segs[i].isEmpty()) continue;
            cur = child(cr, tree, cur, segs[i], true, -1);
            if (cur == null) return null;
        }
        return child(cr, tree, cur, name, false, size);
    }

    private static String child(ContentResolver cr, Uri tree, String parent, String name, boolean dir, long size) {
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        String byName = null;
        int named = 0;
        try (Cursor c = cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), cols, null, null, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                if (!name.equals(c.getString(1))) continue;
                boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                if (isDir != dir) continue;
                if (dir || (size >= 0 && !c.isNull(3) && c.getLong(3) == size)) return c.getString(0);
                byName = c.getString(0);
                named++;
            }
        }
        return named == 1 ? byName : null;
    }

    /**
     * Puts head in place of the file's first oldLen bytes. Same size: written in place. Otherwise the rest of
     * the file is copied to B-Side's cache first, then written back after the new start; if anything fails on
     * the way, the old start and the copy are put back. Returns the file's new size.
     */
    static long writeHead(Context c, Uri u, byte[] head, long oldLen) throws Exception {
        ContentResolver cr = c.getContentResolver();
        ParcelFileDescriptor pfd;
        try {
            pfd = cr.openFileDescriptor(u, "rw");
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            pfd = null;
        }
        if (pfd == null) return rewriteWhole(c, u, head, oldLen); // the app holding the file can't edit in place
        try {
            FileDescriptor fd = pfd.getFileDescriptor();
            long size = Os.fstat(fd).st_size;
            if (oldLen < 0 || oldLen > size) throw new Exception("The file changed. Try again");
            byte[] old = readAt(fd, 0, (int) oldLen);
            if (head.length == oldLen) {
                writeAt(fd, 0, head);
                if (!Arrays.equals(readAt(fd, 0, head.length), head)) {
                    writeAt(fd, 0, old);
                    throw new Exception("The file didn’t save right, so it was left as it was");
                }
                return size;
            }
            long tail = size - oldLen;
            if (c.getCacheDir().getUsableSpace() < tail + (8 << 20)) throw new Exception("Not enough free space to change this file");
            File keep = new File(c.getCacheDir(), "tag-rest.tmp");
            try {
                copy(fd, oldLen, tail, keep);
                try {
                    writeAt(fd, 0, head);
                    copyBack(keep, fd, head.length);
                    Os.ftruncate(fd, head.length + tail);
                    if (Os.fstat(fd).st_size != head.length + tail) throw new Exception("size");
                    return head.length + tail;
                } catch (Exception e) {
                    try { // put the file back as it was
                        writeAt(fd, 0, old);
                        copyBack(keep, fd, oldLen);
                        Os.ftruncate(fd, size);
                    } catch (Exception ignored) {
                    }
                    throw new Exception("The file couldn’t be changed, so it was left as it was");
                }
            } finally {
                //noinspection ResultOfMethodCallIgnored
                keep.delete();
            }
        } finally {
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** For apps that only let a file be replaced as a whole: copies the music aside, then writes it all again. */
    private static long rewriteWhole(Context c, Uri u, byte[] head, long oldLen) throws Exception {
        File keep = new File(c.getCacheDir(), "tag-whole.tmp");
        try {
            long size = 0;
            try (InputStream in = cr(c).openInputStream(u); OutputStream o = new FileOutputStream(keep)) {
                if (in == null) throw new Exception("The file couldn’t be read");
                byte[] b = new byte[1 << 16];
                for (int n; (n = in.read(b)) > 0; ) {
                    o.write(b, 0, n);
                    size += n;
                }
            }
            if (oldLen > size) throw new Exception("The file changed. Try again");
            try {
                writeStream(c, u, head, keep, oldLen);
            } catch (Exception e) {
                try {
                    writeStream(c, u, new byte[0], keep, 0);
                } catch (Exception ignored) {
                }
                throw new Exception("The file couldn’t be changed, so it was left as it was");
            }
            return head.length + size - oldLen;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            keep.delete();
        }
    }

    private static void writeStream(Context c, Uri u, byte[] head, File rest, long skip) throws Exception {
        try (OutputStream o = cr(c).openOutputStream(u, "wt"); InputStream in = new FileInputStream(rest)) {
            if (o == null) throw new Exception("open");
            o.write(head);
            long left = skip;
            while (left > 0) {
                long s = in.skip(left);
                if (s <= 0) throw new Exception("skip");
                left -= s;
            }
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
        }
    }

    private static ContentResolver cr(Context c) {
        return c.getContentResolver();
    }

    private static byte[] readAt(FileDescriptor fd, long at, int len) throws Exception {
        byte[] b = new byte[len];
        int got = 0;
        while (got < len) {
            int n = Os.pread(fd, b, got, len - got, at + got);
            if (n <= 0) throw new Exception("read");
            got += n;
        }
        return b;
    }

    private static void writeAt(FileDescriptor fd, long at, byte[] b) throws Exception {
        int put = 0;
        while (put < b.length) {
            int n = Os.pwrite(fd, b, put, b.length - put, at + put);
            if (n <= 0) throw new Exception("write");
            put += n;
        }
    }

    private static void copy(FileDescriptor fd, long from, long len, File to) throws Exception {
        byte[] b = new byte[1 << 16];
        try (OutputStream o = new FileOutputStream(to)) {
            long done = 0;
            while (done < len) {
                int n = Os.pread(fd, b, 0, (int) Math.min(b.length, len - done), from + done);
                if (n <= 0) throw new Exception("read");
                o.write(b, 0, n);
                done += n;
            }
        }
    }

    private static void copyBack(File from, FileDescriptor fd, long at) throws Exception {
        byte[] b = new byte[1 << 16];
        try (InputStream in = new FileInputStream(from)) {
            long pos = at;
            for (int n; (n = in.read(b)) > 0; ) {
                int put = 0;
                while (put < n) {
                    int w = Os.pwrite(fd, b, put, n - put, pos + put);
                    if (w <= 0) throw new Exception("write");
                    put += w;
                }
                pos += n;
            }
        }
    }
}
