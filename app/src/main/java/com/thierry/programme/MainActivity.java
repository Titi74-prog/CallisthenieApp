package com.thierry.programme;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.ComponentActivity;

public class MainActivity extends ComponentActivity {
    private static final String ASSETS = "file:///android_asset/";
    private WebView webView;
    private HealthBridge health;
    private CaptureBridge capture;
    private String currentPage = "programme";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0f0f13"));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null) {
                    int hash = url.indexOf('#');
                    if (hash >= 0) url = url.substring(0, hash);
                    if (url.endsWith("respiration.html")) currentPage = "respiration";
                    else if (url.endsWith("poids.html")) currentPage = "poids";
                    else if (url.endsWith("seance_extra.html")) currentPage = "extra";
                    else if (url.endsWith("sante.html")) currentPage = "sante";
                    else currentPage = "programme";
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        // Version de l'app lisible par les pages (affichée dans les en-têtes)
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public String version() {
                try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
                catch (Exception e) { return ""; }
            }
            // Réglages persistants côté Android (écriture synchrone, survit à la fermeture de l'appli)
            @JavascriptInterface
            public String getPref(String key) {
                return getSharedPreferences("callisthenie", MODE_PRIVATE).getString(key, null);
            }
            @JavascriptInterface
            public void setPref(String key, String value) {
                getSharedPreferences("callisthenie", MODE_PRIVATE).edit().putString(key, value).commit();
            }
        }, "App");

        // Pont Health Connect → JS (calories, pas, poids, composition, sommeil)
        health = new HealthBridge(this, webView);
        webView.addJavascriptInterface(health, "HealthBridge");

        // v6.3 — Lecture OCR des captures Xiaomi S400 (ML Kit, hors ligne)
        capture = new CaptureBridge(this, webView);
        webView.addJavascriptInterface(capture, "CaptureBridge");

        Intent start = getIntent();
        webView.loadUrl(ASSETS + (isImportIntent(start) ? "poids.html#import" : isHealthIntent(start) ? "sante.html" : "programme.html"));
        setContentView(webView);
        if (isImportIntent(start)) capture.importFile(start.getStringExtra(ImportCaptureActivity.EXTRA_PATH));
    }

    /** Capture partagée depuis Xiaomi Home, relayée par ImportCaptureActivity → onglet Poids (v6.4). */
    private boolean isImportIntent(Intent i) {
        return i != null && ImportCaptureActivity.ACTION_IMPORT.equals(i.getAction())
            && i.getStringExtra(ImportCaptureActivity.EXTRA_PATH) != null;
    }

    /** Health Connect ouvre l'app avec ces actions pour expliquer l'usage des données. */
    private boolean isHealthIntent(Intent i) {
        if (i == null || i.getAction() == null) return false;
        String a = i.getAction();
        return a.equals("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE")
            || a.equals("android.intent.action.VIEW_PERMISSION_USAGE");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (isImportIntent(intent)) {
            setIntent(intent);
            if (!currentPage.equals("poids")) webView.loadUrl(ASSETS + "poids.html#import");
            capture.importFile(intent.getStringExtra(ImportCaptureActivity.EXTRA_PATH));
        } else if (isHealthIntent(intent)) webView.loadUrl(ASSETS + "sante.html");
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!currentPage.equals("programme")) webView.loadUrl(ASSETS + "programme.html");
        else if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); webView.onPause(); }
    @Override protected void onResume() { super.onResume(); webView.onResume(); }
    @Override protected void onDestroy() { if (health != null) health.destroy(); if (capture != null) capture.destroy(); super.onDestroy(); }
}
