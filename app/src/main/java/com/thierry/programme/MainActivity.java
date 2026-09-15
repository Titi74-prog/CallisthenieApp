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
    private TextView btnPoids;
    private String currentPage = "programme";

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

        LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);

        btnProgramme = new TextView(this);
        btnProgramme.setText("🏋️ Programme");
        btnProgramme.setTextSize(14);
        btnProgramme.setTextColor(Color.parseColor("#5C6BC0"));
        btnProgramme.setGravity(Gravity.CENTER);
        btnProgramme.setPadding(16, 12, 16, 12);
        btnProgramme.setLayoutParams(lpBtn);
        btnProgramme.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showPage("programme"); }
        });

        btnNutrition = new TextView(this);
        btnNutrition.setText("🥗 Nutrition");
        btnNutrition.setTextSize(14);
        btnNutrition.setTextColor(Color.parseColor("#6b6a75"));
        btnNutrition.setGravity(Gravity.CENTER);
        btnNutrition.setPadding(16, 12, 16, 12);
        btnNutrition.setLayoutParams(lpBtn);
        btnNutrition.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showPage("nutrition"); }
        });

        btnPoids = new TextView(this);
        btnPoids.setText("⚖️ Poids");
        btnPoids.setTextSize(14);
        btnPoids.setTextColor(Color.parseColor("#6b6a75"));
        btnPoids.setGravity(Gravity.CENTER);
        btnPoids.setPadding(16, 12, 16, 12);
        btnPoids.setLayoutParams(lpBtn);
        btnPoids.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showPage("poids"); }
        });

        navBar.addView(btnProgramme);
        navBar.addView(btnNutrition);
        navBar.addView(btnPoids);

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

    private void showPage(String page) {
        currentPage = page;
        btnProgramme.setTextColor(Color.parseColor(page.equals("programme") ? "#5C6BC0" : "#6b6a75"));
        btnNutrition.setTextColor(Color.parseColor(page.equals("nutrition") ? "#4ECDC4" : "#6b6a75"));
        btnPoids.setTextColor(Color.parseColor(page.equals("poids") ? "#F9A825" : "#6b6a75"));
        webView.loadUrl("file:///android_asset/" + page + ".html");
    }

    @Override
    public void onBackPressed() {
        if (!currentPage.equals("programme")) {
            showPage("programme");
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
