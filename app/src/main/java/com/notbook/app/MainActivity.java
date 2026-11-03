package com.notbook.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen WebView that serves the bundled pages from app assets over a real
 * https origin (so localStorage persists across launches) and keeps links to
 * other sites in the system browser.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/www/index.html";

    private WebView web;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<Integer, HttpURLConnection> live = new ConcurrentHashMap<>();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(0xFF0D100F);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                String host = req.getUrl().getHost();
                // fonts ship inside the app; answer any leftover Google Fonts request at once so nothing waits on the network
                if (host != null && (host.endsWith("googleapis.com") || host.endsWith("gstatic.com"))) {
                    return new WebResourceResponse("text/css", "utf-8",
                            new java.io.ByteArrayInputStream(new byte[0]));
                }
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                if (!req.isForMainFrame()) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        // the model API is called from native code so browser cross-origin rules never block it
        web.addJavascriptInterface(new NativeHttp(), "NativeHttp");

        setContentView(web);

        // always start fresh on the home page (笔记)
        web.loadUrl(START);
    }

    private void js(final String code) {
        web.post(() -> web.evaluateJavascript(code, null));
    }

    /**
     * Minimal streaming HTTP for the page: status, then body chunks (base64), then end or fail,
     * delivered to window.__nh in the top frame. Only https URLs are allowed.
     */
    private class NativeHttp {
        @JavascriptInterface
        public void request(final int id, final String method, final String url, final String headers, final String body) {
            pool.execute(() -> {
                HttpURLConnection c = null;
                try {
                    URL u = new URL(url);
                    if (!"https".equals(u.getProtocol())) throw new Exception("only https is allowed");
                    c = (HttpURLConnection) u.openConnection();
                    live.put(id, c);
                    c.setRequestMethod(method);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(90000);
                    c.setUseCaches(false);
                    JSONObject h = new JSONObject(headers == null || headers.isEmpty() ? "{}" : headers);
                    Iterator<String> keys = h.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        c.setRequestProperty(k, h.getString(k));
                    }
                    if (body != null && !body.isEmpty() && !"GET".equals(method)) {
                        c.setDoOutput(true);
                        try (OutputStream out = c.getOutputStream()) {
                            out.write(body.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    int status = c.getResponseCode();
                    js("window.__nh&&__nh.head(" + id + "," + status + ")");
                    InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                    if (in != null) {
                        try (InputStream src = in) {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = src.read(buf)) > 0) {
                                js("window.__nh&&__nh.data(" + id + ",'" + Base64.encodeToString(buf, 0, n, Base64.NO_WRAP) + "')");
                            }
                        }
                    }
                    js("window.__nh&&__nh.end(" + id + ")");
                } catch (Exception e) {
                    if (live.containsKey(id)) {
                        js("window.__nh&&__nh.fail(" + id + "," + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                    }
                } finally {
                    live.remove(id);
                    if (c != null) c.disconnect();
                }
            });
        }

        @JavascriptInterface
        public void cancel(final int id) {
            final HttpURLConnection c = live.remove(id);
            if (c != null) pool.execute(c::disconnect);
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }
}
