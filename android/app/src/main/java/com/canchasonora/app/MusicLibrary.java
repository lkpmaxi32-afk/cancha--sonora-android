package com.canchasonora.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.JsonWriter;
import android.util.Size;
import android.webkit.WebResourceResponse;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * Lee la música de MediaStore (teléfono y tarjeta de memoria), guarda la lista en un archivo
 * y sirve audio (con Range) y portadas al WebView por direcciones /_musica_/...
 * El WebView nunca recibe la lista completa por el puente: la baja como un archivo JSON.
 */
public class MusicLibrary {
    static final String PREFS = "cancha_lib";
    static final String PATH = "/_musica_/";

    private final Context ctx;
    private final File libFile;
    private final File artDir;
    private final Semaphore artSlots = new Semaphore(2);

    public MusicLibrary(Context c) {
        ctx = c.getApplicationContext();
        libFile = new File(ctx.getCacheDir(), "lib.json");
        artDir = new File(ctx.getCacheDir(), "art");
        //noinspection ResultOfMethodCallIgnored
        artDir.mkdirs();
    }

    public static class ScanResult {
        public int total;
        public int newCount;
        public boolean changed;
        public long maxAdded;
    }

    static List<String> volumes(Context c) {
        List<String> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            Set<String> s = MediaStore.getExternalVolumeNames(c);
            for (String v : s) out.add(v);
        } else {
            out.add("external");
        }
        if (out.isEmpty()) out.add("external");
        return out;
    }

    static Uri audioUri(String vol) {
        return MediaStore.Audio.Media.getContentUri(vol);
    }

    /** Recorre MediaStore y escribe lib.json. Se llama desde un hilo de fondo. */
    public synchronized ScanResult scan() throws IOException {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastSeen = sp.getLong("lastSeen", -1);
        long oldSig = sp.getLong("sig", -1);
        List<String> vols = volumes(ctx);
        ContentResolver cr = ctx.getContentResolver();

        String[] cols;
        if (Build.VERSION.SDK_INT >= 29) {
            cols = new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME};
        } else {
            cols = new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DISPLAY_NAME};
        }

        File tmp = new File(ctx.getCacheDir(), "lib.json.tmp");
        ScanResult res = new ScanResult();
        long sig = 0, maxAdded = 0;
        int newCount = 0, total = 0;
        JsonWriter w = new JsonWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"), 1 << 16));
        try {
            w.beginObject();
            w.name("v").beginArray();
            for (String v : vols) w.value(v);
            w.endArray();
            w.name("t").beginArray();
            for (int vi = 0; vi < vols.size(); vi++) {
                Cursor c = null;
                try {
                    c = cr.query(audioUri(vols.get(vi)), cols, MediaStore.Audio.Media.IS_MUSIC + " != 0", null,
                        MediaStore.Audio.Media.DISPLAY_NAME + " COLLATE NOCASE ASC");
                } catch (Exception e) { c = null; }
                if (c == null) continue;
                try {
                    while (c.moveToNext()) {
                        long id = c.getLong(0);
                        long added = c.getLong(6), mod = c.getLong(7);
                        String folder = c.getString(8);
                        if (Build.VERSION.SDK_INT < 29 && folder != null) {
                            int k = folder.lastIndexOf('/');
                            folder = k >= 0 ? folder.substring(0, k + 1) : "";
                        }
                        w.beginArray();
                        w.value(id).value(vi);
                        w.value(c.getString(1)).value(c.getString(2)).value(c.getString(3));
                        w.value(c.getLong(4)).value(c.getLong(5)).value(added).value(mod);
                        w.value(folder == null ? "" : folder).value(c.getString(9));
                        w.endArray();
                        total++;
                        sig += id * 31 + mod + vi;
                        if (added > maxAdded) maxAdded = added;
                        if (lastSeen >= 0 && added > lastSeen) newCount++;
                    }
                } finally { c.close(); }
            }
            w.endArray();
            w.endObject();
        } finally { w.close(); }
        sig = sig * 31 + total;
        boolean changed = !libFile.exists() || sig != oldSig;
        if (!tmp.renameTo(libFile)) {
            //noinspection ResultOfMethodCallIgnored
            libFile.delete();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(libFile);
        }
        SharedPreferences.Editor ed = sp.edit().putLong("sig", sig).putLong("maxAdded", maxAdded);
        // primera vez: lo que ya existe no cuenta como "nuevo"
        if (lastSeen < 0) ed.putLong("lastSeen", maxAdded);
        ed.apply();
        res.total = total; res.newCount = newCount; res.changed = changed; res.maxAdded = maxAdded;
        return res;
    }

    /** La app ya mostró lo nuevo: no volver a avisar por eso. */
    public void markSeen() {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putLong("lastSeen", sp.getLong("maxAdded", 0)).apply();
    }

    // ---------------------------------------------------------------- servidor interno

    public WebResourceResponse handle(String path, String rangeHeader) {
        try {
            String p = path.substring(PATH.length());
            if (p.equals("lib.json")) return libJson();
            String[] s = p.split("/");
            if (s.length >= 3 && s[0].equals("a")) return audio(s[1], Long.parseLong(s[2]), rangeHeader);
            if (s.length >= 4 && s[0].equals("art")) return art(s[1], Long.parseLong(s[2]), Integer.parseInt(s[3]));
        } catch (Exception ignored) { }
        return notFound();
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        return h;
    }

    private WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers(), new java.io.ByteArrayInputStream(new byte[0]));
    }

    private WebResourceResponse libJson() throws IOException {
        if (!libFile.exists()) return notFound();
        Map<String, String> h = headers();
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse("application/json", "utf-8", 200, "OK", h, new FileInputStream(libFile));
    }

    /** Lee solo el tramo pedido de un archivo, sin cargarlo en memoria. */
    private static class SliceStream extends InputStream {
        private final ParcelFileDescriptor pfd;
        private final FileInputStream in;
        private long left;
        SliceStream(ParcelFileDescriptor pfd, long start, long len) throws IOException {
            this.pfd = pfd;
            this.in = new FileInputStream(pfd.getFileDescriptor());
            FileChannel ch = in.getChannel();
            ch.position(start);
            this.left = len;
        }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
        @Override public int available() { return (int) Math.min(left, 1 << 16); }
        @Override public void close() throws IOException {
            try { in.close(); } finally { pfd.close(); }
        }
    }

    private WebResourceResponse audio(String vol, long id, String range) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Uri uri = ContentUris.withAppendedId(audioUri(vol), id);
        String mime = cr.getType(uri);
        if (mime == null || !mime.startsWith("audio")) mime = "audio/mpeg";
        ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
        if (pfd == null) return notFound();
        long size = pfd.getStatSize();
        if (size < 0) { pfd.close(); return notFound(); }
        long start = 0, end = size - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String r = range.substring(6).split(",")[0].trim();
            int d = r.indexOf('-');
            try {
                if (d == 0) { // últimos N bytes
                    long n = Long.parseLong(r.substring(1));
                    start = Math.max(0, size - n);
                } else {
                    start = Long.parseLong(r.substring(0, d));
                    if (d < r.length() - 1) end = Math.min(end, Long.parseLong(r.substring(d + 1)));
                }
                partial = true;
            } catch (NumberFormatException e) { start = 0; end = size - 1; partial = false; }
        }
        if (start >= size) { pfd.close(); return new WebResourceResponse("text/plain", "utf-8", 416, "Range Not Satisfiable", headers(), new java.io.ByteArrayInputStream(new byte[0])); }
        long len = end - start + 1;
        Map<String, String> h = headers();
        h.put("Accept-Ranges", "bytes");
        h.put("Content-Length", String.valueOf(len));
        if (partial) h.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
        InputStream in = new SliceStream(pfd, start, len);
        return new WebResourceResponse(mime, null, partial ? 206 : 200, partial ? "Partial Content" : "OK", h, in);
    }

    private WebResourceResponse art(String vol, long id, int px) throws Exception {
        px = Math.max(64, Math.min(px, 800));
        String name = vol.replaceAll("[^A-Za-z0-9]", "_") + "_" + id + "_" + px;
        File f = new File(artDir, name + ".jpg");
        File none = new File(artDir, name + ".none");
        if (none.exists()) return notFound();
        if (!f.exists()) {
            artSlots.acquireUninterruptibly();
            try {
                if (!f.exists()) {
                    Bitmap bmp = loadArt(vol, id, px);
                    if (bmp == null) { //noinspection ResultOfMethodCallIgnored
                        none.createNewFile(); return notFound(); }
                    FileOutputStream o = new FileOutputStream(f);
                    try { bmp.compress(Bitmap.CompressFormat.JPEG, 82, o); } finally { o.close(); }
                    bmp.recycle();
                }
            } finally { artSlots.release(); }
        }
        Map<String, String> h = headers();
        h.put("Cache-Control", "max-age=86400");
        return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new FileInputStream(f));
    }

    private Bitmap loadArt(String vol, long id, int px) {
        Uri uri = ContentUris.withAppendedId(audioUri(vol), id);
        if (Build.VERSION.SDK_INT >= 29) {
            try { return ctx.getContentResolver().loadThumbnail(uri, new Size(px, px), null); } catch (Exception ignored) { }
        }
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(ctx, uri);
            byte[] pic = mr.getEmbeddedPicture();
            if (pic == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            int s = 1;
            while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            if (b == null) return null;
            int m = Math.max(b.getWidth(), b.getHeight());
            if (m > px) {
                float k = px / (float) m;
                Bitmap sc = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
                if (sc != b) b.recycle();
                b = sc;
            }
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            try { mr.release(); } catch (Exception ignored) { }
        }
    }

    public void clearArtCache() {
        File[] fs = artDir.listFiles();
        if (fs != null) for (File f : fs) { //noinspection ResultOfMethodCallIgnored
            f.delete(); }
    }
}
