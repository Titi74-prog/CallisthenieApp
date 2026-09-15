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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import android.graphics.Color;
import android.view.View;

public class MainActivity extends Activity {

    private WebView webView;
    private TextView btnProgramme;
    private TextView btnNutrition;
    private boolean showingNutrition = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0f0f13"));

        // Bottom nav bar
        LinearLayout navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(Color.parseColor("#1a1a22"));
        navBar.setPadding(0, 8, 0, 8);

        btnProgramme = new TextView(this);
        btnProgramme.setText("🏋️ Programme");
        btnProgramme.setTextSize(14);
        btnProgramme.setTextColor(Color.parseColor("#5C6BC0"));
        btnProgramme.setGravity(Gravity.CENTER);
        btnProgramme.setPadding(16, 12, 16, 12);
        LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnProgramme.setLayoutParams(lpBtn);
        btnProgramme.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showPage(false); }
        });

        btnNutrition = new TextView(this);
        btnNutrition.setText("🥗 Nutrition");
        btnNutrition.setTextSize(14);
        btnNutrition.setTextColor(Color.parseColor("#6b6a75"));
        btnNutrition.setGravity(Gravity.CENTER);
        btnNutrition.setPadding(16, 12, 16, 12);
        btnNutrition.setLayoutParams(lpBtn);
        btnNutrition.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showPage(true); }
        });

        navBar.addView(btnProgramme);
        navBar.addView(btnNutrition);

        // WebView
        webView = new WebView(this);
        LinearLayout.LayoutParams lpWeb = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        webView.setLayoutParams(lpWeb);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/programme.html");

        root.addView(webView);
        root.addView(navBar);
        setContentView(root);
    }

    private void showPage(boolean nutrition) {
        showingNutrition = nutrition;
        btnProgramme.setTextColor(Color.parseColor(nutrition ? "#6b6a75" : "#5C6BC0"));
        btnNutrition.setTextColor(Color.parseColor(nutrition ? "#4ECDC4" : "#6b6a75"));
        webView.loadUrl("file:///android_asset/" + (nutrition ? "nutrition.html" : "programme.html"));
    }

    @Override
    public void onBackPressed() {
        if (showingNutrition) {
            showPage(false);
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() { super.onPause(); webView.onPause(); }

    @Override
    protected void onResume() { super.onResume(); webView.onResume(); }
}
