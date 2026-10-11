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
        }, "App");

        // Pont Health Connect → JS (calories, pas, poids, composition, sommeil)
        health = new HealthBridge(this, webView);
        webView.addJavascriptInterface(health, "HealthBridge");

        webView.loadUrl(ASSETS + (isHealthIntent(getIntent()) ? "sante.html" : "programme.html"));
        setContentView(webView);
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
        if (isHealthIntent(intent)) webView.loadUrl(ASSETS + "sante.html");
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
    @Override protected void onDestroy() { if (health != null) health.destroy(); super.onDestroy(); }
}
