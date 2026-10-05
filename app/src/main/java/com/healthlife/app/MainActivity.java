package com.healthlife.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "HealthLife";
    private static final int REQ_MIC = 1001;
    private static final int REQ_LOC = 1002;
    private static final int REQ_FILE_CHOOSER = 2001;
    private static final int REQ_PICK_BACKUP = 2002;

    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setUserAgentString(ws.getUserAgentString() + " HealthLifeApp/1.0");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WebView.setWebContentsDebuggingEnabled(false);
        }

        /* ========== 关键：用 https://appassets.androidplatform.net/ 加载页面 ==========
           这样页面来源是 HTTPS，getUserMedia（麦克风）才能被允许。
           原来用 file:///android_asset/ 会被 WebView 判定为不安全来源，麦克风直接失败。
         */
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            /* ========== 授权网页的麦克风 ========== */
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    try {
                        request.grant(request.getResources());
                        Log.d(TAG, "onPermissionRequest granted: " + java.util.Arrays.toString(request.getResources()));
                    } catch (Exception e) {
                        Log.e(TAG, "grant failed", e);
                    }
                });
            }

            /* ========== 关键：文件选择器（衣橱上传照片） ========== */
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent intent = null;
                try {
                    intent = params.createIntent();
                } catch (Exception ignored) {}

                if (intent == null) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                try {
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, "[Web] " + cm.message() + " @" + cm.lineNumber());
                return true;
            }
        });

        /* 通过 assetLoader 提供的 https 地址加载页面 */
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");

        // TTS
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.CHINA);
                ttsReady = (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED);
            }
        });

        requestPermsIfNeeded();
    }

    private void requestPermsIfNeeded() {
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
            return;
        }

        if (requestCode == REQ_PICK_BACKUP) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                readBackupAndSend(data.getData());
            }
            return;
        }
    }

    private void readBackupAndSend(Uri uri) {
        new Thread(() -> {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                br.close();
                final String content = sb.toString();
                final String quoted = JSONObject.quote(content);
                runOnUiThread(() ->
                        webView.evaluateJavascript(
                                "window.onBackupFileContent && window.onBackupFileContent(" + quoted + ")", null));
            } catch (Exception e) {
                Log.e(TAG, "read backup failed", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "读取备份失败", Toast.LENGTH_SHORT).show();
                    webView.evaluateJavascript(
                            "window.onBackupFileError && window.onBackupFileError('读取失败')", null);
                });
            }
        }).start();
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

        /* ---------- 关键：导出备份 → 保存到外部目录 + 分享 ---------- */
        @JavascriptInterface
        public void saveBackup(final String json) {
            try {
                File dir = new File(getExternalFilesDir(null), "backups");
                if (!dir.exists()) dir.mkdirs();
                String name = "健康生活备份_" +
                        new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()) + ".json";
                final File f = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(json.getBytes("UTF-8"));
                }
                runOnUiThread(() -> {
                    try {
                        Uri uri = FileProvider.getUriForFile(MainActivity.this,
                                getPackageName() + ".fileprovider", f);
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("application/json");
                        share.putExtra(Intent.EXTRA_STREAM, uri);
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(share, "保存备份文件到…"));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this,
                                "备份已保存: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "saveBackup fail", e);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "备份保存失败", Toast.LENGTH_SHORT).show());
            }
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

        /* ---------- 关键：导入备份 → 原生文件选择器 ---------- */
        @JavascriptInterface
        public void pickBackupFile() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivityForResult(intent, REQ_PICK_BACKUP);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                }
            });
        }

        /* ---------- 提醒开关（占位） ---------- */
        @JavascriptInterface
        public void setSitReminder(boolean on) { }

        @JavascriptInterface
        public void setBackupReminder(boolean on) { }

        /* ---------- AI 聊天（暂未接入 SparkChain） ---------- */
        @JavascriptInterface
        public void sendChat(final String text) {
            Log.d(TAG, "sendChat: " + text);
            webView.post(() -> webView.evaluateJavascript(
                    "window.onNativeChatError && window.onNativeChatError('AI 聊天需要接入讯飞 SparkChain，请参考官方文档在 MainActivity 中初始化')", null));
        }

        @JavascriptInterface
        public void sendStyling(final String prompt) {
            Log.d(TAG, "sendStyling");
            webView.post(() -> webView.evaluateJavascript(
                    "window.onNativeChatError && window.onNativeChatError('AI 搭配需要接入讯飞 SparkChain', 'styling')", null));
        }
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