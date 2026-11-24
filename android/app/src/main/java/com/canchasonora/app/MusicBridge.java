package com.canchasonora.app;

import android.webkit.JavascriptInterface;

/** window.AndroidLib: lo que la página puede pedirle al teléfono. */
public class MusicBridge {
    private final MainActivity act;

    public MusicBridge(MainActivity a) { act = a; }

    @JavascriptInterface public boolean hasPermission() { return act.hasAudioPermission(); }
    @JavascriptInterface public void requestPermission() { act.askAudioPermission(); }
    @JavascriptInterface public void scan() { act.scanAsync(); }
    @JavascriptInterface public void markSeen() { act.library().markSeen(); }
    @JavascriptInterface public void share(String vol, String id, String title) { act.shareTrack(vol, id, title); }
    @JavascriptInterface public void deleteTrack(String vol, String id, String key) { act.deleteTrack(vol, id, key); }
}
