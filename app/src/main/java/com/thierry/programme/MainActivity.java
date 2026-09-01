package com.thierry.programme;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.app.Activity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
public class MainActivity extends Activity {
    private WebView webView;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(Color.parseColor("#F8F9FA"));
        webView.setLongClickable(false);
        webView.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { return true; } });
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/programme.html");
    }
    private void applyImmersive() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) { applyImmersive(); }
    }
    @Override
    public void onBackPressed() {
        webView.evaluateJavascript("(window.handleBack?window.handleBack():false)", new ValueCallback<String>() { public void onReceiveValue(String value) { if (!"true".equals(value)) { MainActivity.super.onBackPressed(); } } });
    }
    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }
    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        applyImmersive();
    }
    public class AndroidBridge {
        @JavascriptInterface
        public void haptic() {
            runOnUiThread(new Runnable() { public void run() { webView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); } });
        }
    }
}
