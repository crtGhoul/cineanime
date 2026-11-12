package com.cineanime.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Hardware-accelerated Android TV & Google TV WebView shell for CineAnime.
 * Loads live production assets directly so GitHub Pages updates apply automatically.
 */
public class MainActivity extends Activity {
    private static final String HOME_URL = "https://crtghoul.github.io/cineanime/";
    private static final String ALLOWED_HOST = "crtghoul.github.io";

    private FrameLayout rootLayout;
    private WebView webView;
    private FrameLayout fullscreenContainer;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private LinearLayout errorOverlay;
    private TextView errorDetailsView;
    private Button retryButton;

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

        setContentView(rootLayout);
        configureWebView();
        webView.requestFocus();
        webView.loadUrl(HOME_URL);
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
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                // Drop popup ad / rogue window creation attempts unconditionally.
                return false;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) {
                    return true;
                }
                String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "";
                String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";

                if ("https".equals(scheme) && ALLOWED_HOST.equals(host)) {
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
                if (url != null && url.startsWith("https://" + ALLOWED_HOST)) {
                    mainFrameErrorOccurred = false;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!mainFrameErrorOccurred && url != null && url.startsWith("https://" + ALLOWED_HOST)) {
                    hideErrorOverlay();
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
                    showErrorOverlay(desc);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request != null && request.isForMainFrame() && errorResponse != null && errorResponse.getStatusCode() >= 400) {
                    mainFrameErrorOccurred = true;
                    showErrorOverlay("HTTP " + errorResponse.getStatusCode() + " while loading CineAnime.");
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                mainFrameErrorOccurred = true;
                showErrorOverlay("TLS certificate verification failed.");
            }
        });
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

        retryButton = new Button(this);
        retryButton.setText("Retry Connection");
        retryButton.setTextColor(Color.WHITE);
        retryButton.setTextSize(17f);
        retryButton.setPadding(56, 22, 56, 22);
        retryButton.setFocusable(true);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#e50914"));
        btnBg.setCornerRadius(18f);
        retryButton.setBackground(btnBg);
        retryButton.setOnClickListener(v -> {
            mainFrameErrorOccurred = false;
            hideErrorOverlay();
            webView.clearCache(false);
            webView.loadUrl(HOME_URL);
        });
        errorOverlay.addView(retryButton);
    }

    private void showErrorOverlay(String reason) {
        if (errorDetailsView != null) {
            errorDetailsView.setText(reason + "\nEndpoint: " + HOME_URL);
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
