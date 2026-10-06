package com.thierry.programme;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.graphics.Color;

public class MainActivity extends Activity {
    private WebView webView;
    private String currentPage = "programme";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
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
        webView.loadUrl("file:///android_asset/programme.html");

        setContentView(webView);
    }

    @Override
    public void onBackPressed() {
        if (!currentPage.equals("programme")) webView.loadUrl("file:///android_asset/programme.html");
        else if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); webView.onPause(); }
    @Override protected void onResume() { super.onResume(); webView.onResume(); }
}
