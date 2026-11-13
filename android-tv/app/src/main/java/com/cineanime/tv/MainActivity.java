package com.cineanime.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hardware-accelerated Android TV & Google TV WebView shell for CineAnime.
 * Supports automatic multi-mirror failover, custom endpoint configuration, and an embedded offline bundle.
 */
public class MainActivity extends Activity {
    private static final String PREFS_NAME = "cineanime_tv_prefs";
    private static final String KEY_ACTIVE_MIRROR = "active_mirror_url";
    private static final String KEY_CUSTOM_MIRROR = "custom_mirror_url";
    private static final String OFFLINE_HOST = "offline.cineanime.local";
    private static final List<String> DEFAULT_MIRRORS = Arrays.asList(
            "https://crtghoul.github.io/cineanime/",
            "https://velazquez-lab.pages.dev/cineanime/",
            "https://" + OFFLINE_HOST + "/"
    );

    private FrameLayout rootLayout;
    private WebView webView;
    private FrameLayout fullscreenContainer;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private LinearLayout errorOverlay;
    private TextView errorDetailsView;
    private Button retryButton;
    private Button switchMirrorButton;
    private Button customMirrorButton;

    private List<String> activeMirrors = new ArrayList<>();
    private int mirrorIndex = 0;
    private String currentHomeUrl = DEFAULT_MIRRORS.get(0);

    private long lastBackPressMs = 0L;
    private boolean mainFrameErrorOccurred = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveUi();

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor("#09090b"));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#09090b"));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        fullscreenContainer.setVisibility(View.GONE);
        rootLayout.addView(fullscreenContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        buildErrorOverlay();
        rootLayout.addView(errorOverlay);

        refreshMirrorCandidates();
        setContentView(rootLayout);
        configureWebView();
        webView.requestFocus();
        loadMirrorAt(0);
    }

    private void refreshMirrorCandidates() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String custom = normalizeMirrorUrl(prefs.getString(KEY_CUSTOM_MIRROR, ""));
        String lastActive = normalizeMirrorUrl(prefs.getString(KEY_ACTIVE_MIRROR, ""));
        if (lastActive.contains("crtghoul.codeberg.page")
                || lastActive.contains("cineanime.pages.dev")
                || lastActive.contains(OFFLINE_HOST)) {
            lastActive = "";
        }
        List<String> list = new ArrayList<>();
        if (!custom.isEmpty()) list.add(custom);
        if (!lastActive.isEmpty() && !list.contains(lastActive)) list.add(lastActive);
        for (String def : DEFAULT_MIRRORS) {
            if (!list.contains(def)) list.add(def);
        }
        activeMirrors = list;
        if (mirrorIndex >= activeMirrors.size()) mirrorIndex = 0;
        currentHomeUrl = activeMirrors.get(mirrorIndex);
    }

    private String normalizeMirrorUrl(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.isEmpty()) return "";
        if (!t.startsWith("https://") && !t.startsWith("http://")) {
            t = "https://" + t;
        }
        Uri parsed = Uri.parse(t);
        if (parsed == null || parsed.getHost() == null) return "";
        if (t.endsWith(".html")) return t;
        return t.endsWith("/") ? t : (t + "/");
    }

    private boolean isAllowedAppHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase();
        if ("crtghoul.github.io".equals(h)
                || OFFLINE_HOST.equals(h)
                || "crtghoul.codeberg.page".equals(h)
                || h.endsWith(".pages.dev")
                || h.endsWith(".netlify.app")
                || h.endsWith(".vercel.app")
                || "localhost".equals(h)
                || "127.0.0.1".equals(h)) {
            return true;
        }
        for (String url : activeMirrors) {
            Uri u = Uri.parse(url);
            if (u != null && u.getHost() != null && h.equals(u.getHost().toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean isAppOriginUrl(String url) {
        if (url == null) return false;
        Uri u = Uri.parse(url);
        return u != null && isAllowedAppHost(u.getHost());
    }

    private void loadMirrorAt(int index) {
        if (activeMirrors.isEmpty()) refreshMirrorCandidates();
        mirrorIndex = Math.max(0, Math.min(index, activeMirrors.size() - 1));
        currentHomeUrl = activeMirrors.get(mirrorIndex);
        mainFrameErrorOccurred = false;
        webView.loadUrl(currentHomeUrl);
    }

    private boolean tryFailoverMirror(String reason) {
        if (mirrorIndex + 1 < activeMirrors.size()) {
            final int nextIdx = mirrorIndex + 1;
            Toast.makeText(this, "Primary offline — trying backup mirror " + (nextIdx + 1), Toast.LENGTH_SHORT).show();
            webView.post(() -> loadMirrorAt(nextIdx));
            return true;
        }
        showErrorOverlay(reason);
        return false;
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(settings.getUserAgentString() + " CineAnimeTV/1.0");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        // Clear transient HTTP file cache on launch while preserving LocalStorage/IndexedDB state.
        webView.clearCache(false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    onHideCustomView();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                fullscreenContainer.removeAllViews();
                fullscreenContainer.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenContainer.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);
                applyImmersiveUi();
            }

            @Override
            public void onHideCustomView() {
                exitFullscreenCustomView();
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    try {
                        request.grant(request.getResources());
                    } catch (Exception e) {
                        // ignore if destroyed
                    }
                });
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                // Drop popup ad / rogue window creation attempts unconditionally.
                return false;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) {
                    return super.shouldInterceptRequest(view, request);
                }
                Uri uri = request.getUrl();
                String host = uri.getHost();
                if (host != null && OFFLINE_HOST.equalsIgnoreCase(host)) {
                    String rawPath = uri.getPath();
                    if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) {
                        rawPath = "/index.html";
                    }
                    String assetPath = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
                    if (assetPath.contains("..")) {
                        return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", null, null);
                    }
                    try {
                        InputStream stream = getAssets().open(assetPath);
                        String mime = guessAssetMimeType(assetPath);
                        Map<String, String> headers = new HashMap<>();
                        headers.put("Access-Control-Allow-Origin", "*");
                        headers.put("Cache-Control", "no-store");
                        return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, stream);
                    } catch (Exception ex) {
                        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null);
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) {
                    return true;
                }
                String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "";
                String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";

                if ("https".equals(scheme) && isAllowedAppHost(host)) {
                    return false;
                }
                if (("http".equals(scheme) || "https".equals(scheme)) && ("localhost".equals(host) || "127.0.0.1".equals(host))) {
                    return false;
                }

                // Allow explicit external player or source repository handoffs without trapping WebView.
                if ("stremio".equals(scheme) || "magnet".equals(scheme) || "vlc".equals(scheme)
                        || ("https".equals(scheme) && ("github.com".equals(host) || host.endsWith(".github.com")))) {
                    openExternalIntent(uri);
                    return true;
                }

                // Block rogue ad-network redirects attempting to hijack the top frame.
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (isAppOriginUrl(url)) {
                    mainFrameErrorOccurred = false;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!mainFrameErrorOccurred && isAppOriginUrl(url)) {
                    hideErrorOverlay();
                    if (!currentHomeUrl.contains(OFFLINE_HOST)) {
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                .putString(KEY_ACTIVE_MIRROR, currentHomeUrl)
                                .apply();
                    }
                }
                webView.evaluateJavascript("document.body && document.body.classList.add('tv-webview-mode');", null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    mainFrameErrorOccurred = true;
                    String desc = error != null && error.getDescription() != null
                            ? error.getDescription().toString()
                            : "Unable to reach CineAnime server.";
                    tryFailoverMirror(desc);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request != null && request.isForMainFrame() && errorResponse != null && errorResponse.getStatusCode() >= 400) {
                    mainFrameErrorOccurred = true;
                    tryFailoverMirror("HTTP " + errorResponse.getStatusCode() + " while loading CineAnime.");
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                mainFrameErrorOccurred = true;
                tryFailoverMirror("TLS certificate verification failed.");
            }
        });
    }

    private String guessAssetMimeType(String assetPath) {
        String p = assetPath != null ? assetPath.toLowerCase() : "";
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".css")) return "text/css";
        return "application/octet-stream";
    }

    private void openExternalIntent(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(this, "No compatible app installed on TV for: " + uri.getScheme(), Toast.LENGTH_SHORT).show();
        }
    }

    private void buildErrorOverlay() {
        errorOverlay = new LinearLayout(this);
        errorOverlay.setOrientation(LinearLayout.VERTICAL);
        errorOverlay.setGravity(Gravity.CENTER);
        errorOverlay.setPadding(64, 48, 64, 48);
        errorOverlay.setBackgroundColor(Color.parseColor("#09090b"));
        errorOverlay.setVisibility(View.GONE);

        TextView title = new TextView(this);
        title.setText("CineAnime Offline");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28f);
        title.setGravity(Gravity.CENTER);
        errorOverlay.addView(title);

        errorDetailsView = new TextView(this);
        errorDetailsView.setText("Check Wi-Fi connection and retry.");
        errorDetailsView.setTextColor(Color.parseColor("#a1a1aa"));
        errorDetailsView.setTextSize(16f);
        errorDetailsView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = 20;
        detailLp.bottomMargin = 36;
        errorOverlay.addView(errorDetailsView, detailLp);

        retryButton = createTvActionButton("Retry Primary", "#e50914");
        retryButton.setOnClickListener(v -> {
            hideErrorOverlay();
            webView.clearCache(false);
            loadMirrorAt(0);
        });
        errorOverlay.addView(retryButton);

        LinearLayout secondaryRow = new LinearLayout(this);
        secondaryRow.setOrientation(LinearLayout.HORIZONTAL);
        secondaryRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = 20;

        switchMirrorButton = createTvActionButton("Try Next Mirror", "#27272a");
        switchMirrorButton.setOnClickListener(v -> {
            int nextIdx = (mirrorIndex + 1) % Math.max(1, activeMirrors.size());
            hideErrorOverlay();
            loadMirrorAt(nextIdx);
        });
        secondaryRow.addView(switchMirrorButton);

        customMirrorButton = createTvActionButton("Set Custom URL", "#27272a");
        LinearLayout.LayoutParams customLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        customLp.leftMargin = 20;
        customMirrorButton.setLayoutParams(customLp);
        customMirrorButton.setOnClickListener(v -> promptCustomMirrorUrl());
        secondaryRow.addView(customMirrorButton);

        errorOverlay.addView(secondaryRow, rowLp);
    }

    private Button createTvActionButton(String label, String hexBg) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(16f);
        btn.setPadding(44, 20, 44, 20);
        btn.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(hexBg));
        bg.setCornerRadius(16f);
        btn.setBackground(bg);
        return btn;
    }

    private void promptCustomMirrorUrl() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://your-mirror.pages.dev/");
        input.setText(currentHomeUrl);

        new AlertDialog.Builder(this)
                .setTitle("Custom CineAnime Mirror URL")
                .setMessage("Enter a backup GitHub Pages, Cloudflare Pages, or Codeberg URL:")
                .setView(input)
                .setPositiveButton("Save & Load", (d, w) -> {
                    String normalized = normalizeMirrorUrl(input.getText() != null ? input.getText().toString() : "");
                    if (normalized.isEmpty()) {
                        Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString(KEY_CUSTOM_MIRROR, normalized)
                            .putString(KEY_ACTIVE_MIRROR, normalized)
                            .apply();
                    mirrorIndex = 0;
                    refreshMirrorCandidates();
                    hideErrorOverlay();
                    loadMirrorAt(0);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showErrorOverlay(String reason) {
        if (errorDetailsView != null) {
            errorDetailsView.setText(reason + "\nTried endpoint (" + (mirrorIndex + 1) + "/" + activeMirrors.size() + "): " + currentHomeUrl);
        }
        errorOverlay.setVisibility(View.VISIBLE);
        if (retryButton != null) {
            retryButton.requestFocus();
        }
    }

    private void hideErrorOverlay() {
        if (errorOverlay != null) {
            errorOverlay.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();

            if (code == KeyEvent.KEYCODE_BACK) {
                handleTvBack();
                return true;
            }

            String mediaAction = mapMediaKeyAction(code);
            if (mediaAction != null) {
                dispatchMediaActionToJs(mediaAction);
                return true;
            }

            if (code == KeyEvent.KEYCODE_MENU) {
                dispatchJs("window.__cineHandleTvMenu ? window.__cineHandleTvMenu() : (window.openAddonModal && openAddonModal());");
                return true;
            }

            if (isDpadKey(code) && errorOverlay.getVisibility() != View.VISIBLE && customView == null) {
                if (!webView.hasFocus()) {
                    webView.requestFocus();
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isDpadKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER;
    }

    private String mapMediaKeyAction(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                return "play_pause";
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return "play";
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return "pause";
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return "next";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return "prev";
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return "ff";
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return "rew";
            case KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD:
                return "skip";
            default:
                return null;
        }
    }

    private void dispatchMediaActionToJs(String action) {
        dispatchJs("window.__cineHandleMediaKey && window.__cineHandleMediaKey('" + action + "');");
    }

    private boolean exitFullscreenCustomView() {
        if (customView == null) {
            return false;
        }
        fullscreenContainer.setVisibility(View.GONE);
        fullscreenContainer.removeView(customView);
        customView = null;
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        webView.setVisibility(View.VISIBLE);
        webView.requestFocus();
        applyImmersiveUi();
        return true;
    }

    private void handleTvBack() {
        if (exitFullscreenCustomView()) {
            return;
        }

        if (errorOverlay.getVisibility() == View.VISIBLE) {
            promptExitOrFinish();
            return;
        }

        webView.evaluateJavascript(
                "(function(){ return window.__cineHandleTvBack ? !!window.__cineHandleTvBack() : false; })()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if ("true".equals(value)) {
                            return;
                        }
                        if (webView != null && webView.canGoBack()) {
                            webView.goBack();
                            return;
                        }
                        promptExitOrFinish();
                    }
                });
    }

    private void promptExitOrFinish() {
        long now = SystemClock.uptimeMillis();
        if (now - lastBackPressMs < 2000L) {
            finish();
        } else {
            lastBackPressMs = now;
            Toast.makeText(this, "Press Back again to exit CineAnime", Toast.LENGTH_SHORT).show();
        }
    }

    private void dispatchJs(String script) {
        if (webView != null) {
            webView.evaluateJavascript(script, null);
        }
    }

    private void applyImmersiveUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
