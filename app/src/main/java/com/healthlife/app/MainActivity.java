package com.healthlife.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "HealthLife";
    private static final int REQ_MIC = 1001;
    private static final int REQ_LOC = 1002;

    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setUserAgentString(ws.getUserAgentString() + " HealthLifeApp/1.0");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WebView.setWebContentsDebuggingEnabled(false);
        }

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // 自动授予 web 页面的麦克风等权限
                runOnUiThread(() -> {
                    try { request.grant(request.getResources()); }
                    catch (Exception e) { Log.e(TAG, "grant failed", e); }
                });
            }
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, "[Web] " + cm.message() + " @" + cm.lineNumber());
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");

        // TTS
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.CHINA);
                ttsReady = (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED);
            }
        });

        // 权限申请
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                 Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
        }
    }

    /** 简单 KV 存储：所有 app_xxx 数据落到 files/kv/xxx.txt */
    private File kvFile(String key) {
        File dir = new File(getFilesDir(), "kv");
        if (!dir.exists()) dir.mkdirs();
        String safe = key.replaceAll("[^a-zA-Z0-9_.\\-]", "_");
        return new File(dir, safe + ".txt");
    }

    public class Bridge {

        /* ---------- 持久化 ---------- */
        @JavascriptInterface
        public String load(String key) {
            try (FileInputStream in = new FileInputStream(kvFile(key))) {
                byte[] buf = new byte[in.available()];
                int n = in.read(buf);
                if (n <= 0) return null;
                return new String(buf, 0, n, "UTF-8");
            } catch (Exception e) { return null; }
        }

        @JavascriptInterface
        public void save(String key, String value) {
            try (FileOutputStream out = new FileOutputStream(kvFile(key))) {
                out.write(value.getBytes("UTF-8"));
            } catch (Exception e) { Log.e(TAG, "save fail", e); }
        }

        @JavascriptInterface
        public void remove(String key) {
            try { kvFile(key).delete(); } catch (Exception ignored) {}
        }

        /* ---------- TTS ---------- */
        @JavascriptInterface
        public void speak(final String text) {
            runOnUiThread(() -> {
                if (ttsReady && tts != null && text != null) {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.currentTimeMillis());
                }
            });
        }

        /* ---------- 振动 ---------- */
        @JavascriptInterface
        public void vibrate() {
            runOnUiThread(() -> {
                try {
                    Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    if (v == null) return;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(300);
                    }
                } catch (Exception ignored) {}
            });
        }

        /* ---------- 备份 ---------- */
        @JavascriptInterface
        public void saveBackup(String json) {
            try {
                File f = new File(getFilesDir(), "backup_" + System.currentTimeMillis() + ".json");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(json.getBytes("UTF-8"));
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "备份已保存: " + f.getName(), Toast.LENGTH_LONG).show());
            } catch (Exception e) { Log.e(TAG, "saveBackup fail", e); }
        }

        @JavascriptInterface
        public void saveDailyBackup(String json) {
            try {
                File f = new File(getFilesDir(), "daily_backup.json");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(json.getBytes("UTF-8"));
                }
            } catch (Exception ignored) {}
        }

        /* ---------- 提醒开关（占位，App 前台时由 JS 自己处理） ---------- */
        @JavascriptInterface
        public void setSitReminder(boolean on) { /* no-op */ }

        @JavascriptInterface
        public void setBackupReminder(boolean on) { /* no-op */ }

        /* ---------- AI（未配置时给 JS 回一个错误提示） ---------- */
        @JavascriptInterface
        public void sendChat(final String text) {
            Log.d(TAG, "sendChat: " + text);
            webView.post(() -> webView.evaluateJavascript(
                    "window.onNativeChatError && window.onNativeChatError('AI 服务未配置，请接入讯飞 SparkChain 后使用')", null));
        }

        @JavascriptInterface
        public void sendStyling(final String prompt) {
            Log.d(TAG, "sendStyling");
            webView.post(() -> webView.evaluateJavascript(
                    "window.onNativeChatError && window.onNativeChatError('AI 服务未配置', 'styling')", null));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 权限结果交给 WebChromeClient 处理即可
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {} }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}