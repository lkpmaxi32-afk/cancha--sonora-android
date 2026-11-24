package com.canchasonora.app;

import android.Manifest;
import android.app.RecoverableSecurityException;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends BridgeActivity {

    static volatile boolean visible = false;
    private static final int REQ_PERM = 11, REQ_DEL = 12, REQ_NOTIF = 13;

    private MusicLibrary library;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String pendingKey, pendingVol, pendingId;

    MusicLibrary library() { return library; }

    /** Responde /_musica_/... (lista, audio con Range y portadas) sin pasar por la red. */
    private class LibClient extends BridgeWebViewClient {
        LibClient(Bridge b) { super(b); }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            String p = u.getPath();
            if (p != null && p.startsWith(MusicLibrary.PATH)) {
                return library.handle(p, request.getRequestHeaders().get("Range"));
            }
            return super.shouldInterceptRequest(view, request);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        library = new MusicLibrary(this);

        WebView webView = getBridge().getWebView();
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        getBridge().setWebViewClient(new LibClient(getBridge()));
        webView.addJavascriptInterface(new MediaBridge(this), "AndroidMedia");
        webView.addJavascriptInterface(new MusicBridge(this), "AndroidLib");

        MediaService.setListener(action -> js("window.__onNativeMedia && window.__onNativeMedia('" + action + "')"));

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        if (hasAudioPermission()) NewMusicJob.schedule(this);
    }

    void js(final String code) {
        runOnUiThread(() -> {
            try { getBridge().getWebView().evaluateJavascript(code, null); } catch (Exception ignored) { }
        });
    }

    // ------------------------------------------------------------ permisos

    boolean hasAudioPermission() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED;
    }

    void askAudioPermission() {
        runOnUiThread(() -> {
            List<String> l = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.READ_MEDIA_AUDIO);
            else {
                l.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                if (Build.VERSION.SDK_INT <= 28) l.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            ActivityCompat.requestPermissions(this, l.toArray(new String[0]), REQ_PERM);
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_PERM) {
            boolean ok = hasAudioPermission();
            if (ok) NewMusicJob.schedule(this);
            js("window.__onPerm && window.__onPerm(" + ok + ")");
        }
    }

    // ------------------------------------------------------------ búsqueda de música

    void scanAsync() {
        worker.execute(() -> {
            try {
                MusicLibrary.ScanResult r = library.scan();
                js("window.__onScan && window.__onScan(" + r.total + "," + r.newCount + "," + r.changed + ",true)");
            } catch (Exception e) {
                js("window.__onScan && window.__onScan(0,0,false,false)");
            }
        });
    }

    // ------------------------------------------------------------ compartir (WhatsApp)

    void shareTrack(String vol, String id, String title) {
        runOnUiThread(() -> {
            try {
                Uri uri = ContentUris.withAppendedId(MusicLibrary.audioUri(vol), Long.parseLong(id));
                String mime = getContentResolver().getType(uri);
                if (mime == null) mime = "audio/*";
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.putExtra(Intent.EXTRA_TEXT, title);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent wa = new Intent(i).setPackage("com.whatsapp");
                try {
                    startActivity(wa);
                } catch (ActivityNotFoundException e) {
                    startActivity(Intent.createChooser(i, "Compartir canción"));
                }
            } catch (Exception e) {
                js("window.__toast && window.__toast('No pude compartir la canción')");
            }
        });
    }

    // ------------------------------------------------------------ eliminar

    void deleteTrack(String vol, String id, String key) {
        runOnUiThread(() -> {
            pendingVol = vol; pendingId = id; pendingKey = key;
            Uri uri = ContentUris.withAppendedId(MusicLibrary.audioUri(vol), Long.parseLong(id));
            ContentResolver cr = getContentResolver();
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    IntentSender is = MediaStore.createDeleteRequest(cr, Collections.singletonList(uri)).getIntentSender();
                    startIntentSenderForResult(is, REQ_DEL, null, 0, 0, 0);
                } else {
                    try {
                        boolean ok = cr.delete(uri, null, null) > 0;
                        finishDelete(ok);
                    } catch (SecurityException se) {
                        if (Build.VERSION.SDK_INT >= 29 && se instanceof RecoverableSecurityException) {
                            IntentSender is = ((RecoverableSecurityException) se).getUserAction().getActionIntent().getIntentSender();
                            startIntentSenderForResult(is, REQ_DEL, null, 0, 0, 0);
                        } else finishDelete(false);
                    }
                }
            } catch (Exception e) {
                finishDelete(false);
            }
        });
    }

    private void finishDelete(boolean ok) {
        String key = pendingKey;
        pendingKey = null;
        if (key == null) return;
        js("window.__onDeleted && window.__onDeleted(" + org.json.JSONObject.quote(key) + "," + ok + ")");
        if (ok) scanAsync();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_DEL || pendingKey == null) return;
        if (result != RESULT_OK) { finishDelete(false); return; }
        if (Build.VERSION.SDK_INT >= 30) { finishDelete(true); return; }
        // Android 10: el usuario dio permiso, hay que repetir el borrado
        try {
            Uri uri = ContentUris.withAppendedId(MusicLibrary.audioUri(pendingVol), Long.parseLong(pendingId));
            finishDelete(getContentResolver().delete(uri, null, null) > 0);
        } catch (Exception e) { finishDelete(false); }
    }

    // ------------------------------------------------------------ ciclo de vida

    /** Evita que el WebView se pause al minimizar o apagar la pantalla: la música sigue. */
    private void keepWebViewAlive() {
        try {
            WebView w = getBridge().getWebView();
            if (w != null) { w.onResume(); w.resumeTimers(); }
        } catch (Exception ignored) { }
    }

    @Override public void onResume() { super.onResume(); visible = true; }

    @Override
    public void onPause() {
        visible = false;
        super.onPause();
        keepWebViewAlive();
    }

    @Override
    public void onStop() {
        super.onStop();
        keepWebViewAlive();
    }

    @Override
    public void onDestroy() {
        MediaService.setListener(null);
        MediaService.stop(this);
        worker.shutdown();
        super.onDestroy();
    }
}
