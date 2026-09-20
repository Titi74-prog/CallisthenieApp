package com.thierry.programme;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import android.graphics.Color;
import android.view.View;

public class MainActivity extends Activity {
    private WebView webView;
    private TextView btnProgramme, btnNutrition, btnPoids;
    private String currentPage = "programme";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0f0f13"));

        webView = new WebView(this);
        LinearLayout.LayoutParams lpWeb = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        webView.setLayoutParams(lpWeb);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/programme.html");

        LinearLayout navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(Color.parseColor("#1a1a22"));
        navBar.setPadding(0, 8, 0, 8);
        LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);

        btnProgramme = makeNavBtn("🏋️ Programme", "#5C6BC0", lpBtn, () -> showPage("programme"));
        btnNutrition = makeNavBtn("🥗 Nutrition", "#6b6a75", lpBtn, () -> showPage("nutrition"));
        btnPoids = makeNavBtn("⚖️ Poids", "#6b6a75", lpBtn, () -> showPage("poids"));

        navBar.addView(btnProgramme);
        navBar.addView(btnNutrition);
        navBar.addView(btnPoids);

        root.addView(webView);
        root.addView(navBar);
        setContentView(root);
    }

    private TextView makeNavBtn(String label, String color, LinearLayout.LayoutParams lp, Runnable action) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextSize(13);
        btn.setTextColor(Color.parseColor(color));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(8, 12, 8, 12);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> action.run());
        return btn;
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
        if (!currentPage.equals("programme")) showPage("programme");
        else if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); webView.onPause(); }
    @Override protected void onResume() { super.onResume(); webView.onResume(); }
}
