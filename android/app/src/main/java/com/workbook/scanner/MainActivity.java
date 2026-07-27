package com.workbook.scanner;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * index.html 을 감싸는 얇은 껍데기.
 *
 * 파일을 file:// 로 열면 브라우저가 "안전하지 않은 출처"로 보아 카메라와
 * 저장소를 막는다. 그래서 WebViewAssetLoader 로 https://appassets.androidplatform.net/
 * 아래에 얹어 https 와 똑같은 조건에서 돌아가게 한다.
 */
public class MainActivity extends Activity {

    private static final String BASE = "https://appassets.androidplatform.net/assets/";
    private static final int REQ_FILE = 1001;
    private static final int REQ_CAMERA = 1002;
    private static final String[] CAMERA_ONLY = { PermissionRequest.RESOURCE_VIDEO_CAPTURE };

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingCamera;
    private final ArrayList<Uri> staged = new ArrayList<>();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        web = new WebView(this);
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                    // IndexedDB(작업 자동 저장)
        s.setMediaPlaybackRequiresUserGesture(false);    // 카메라 미리보기 자동 재생

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    // 페이지가 요청한 것을 통째로 승인하지 않는다.
                    // 이 앱에 필요한 건 카메라뿐이고, 마이크 등은 줄 이유가 없다.
                    boolean wantsCamera = false;
                    for (String r : req.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsCamera = true;
                    }
                    if (!wantsCamera) { req.deny(); return; }

                    if (hasCamera()) {
                        req.grant(CAMERA_ONLY);
                    } else {
                        pendingCamera = req;             // OS 권한부터 받고 다시 시도
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl(BASE + "index.html");

        // Android 13+ 는 예측형 뒤로가기를 쓰며, targetSdk 35 에서는 기본으로 켜진다.
        // 이때 onBackPressed() 는 더 이상 호출되지 않으므로 콜백을 직접 등록해야 한다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerBackCallback();

        if (!hasCamera()) requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    private boolean hasCamera() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_CAMERA || pendingCamera == null) return;
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (ok) pendingCamera.grant(CAMERA_ONLY);
        else pendingCamera.deny();
        pendingCamera = null;
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(result, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(req, result, data);
    }

    private void registerBackCallback() {
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
    }

    /** 뒤로가기: 편집·촬영 화면이 열려 있으면 그것부터 닫고, 없으면 앱을 끝낸다 */
    private void handleBack() {
        web.evaluateJavascript(
                "(window.__handleBack && window.__handleBack()) ? '1' : '0'",
                value -> { if (value == null || !value.contains("1")) finish(); });
    }

    /** Android 12 이하 경로 */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    /** 웹 쪽에서 만든 PDF를 안드로이드 공유 시트로 넘긴다 */
    private class Bridge {

        @JavascriptInterface
        public void beginShare() {
            staged.clear();
        }

        /** 파일 하나씩 받는다(한 번에 큰 문자열을 넘기지 않으려고 나눠서 받음) */
        @JavascriptInterface
        public void addFile(String name, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                File dir = new File(getCacheDir(), "share");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("폴더 생성 실패");
                File f = new File(dir, name.replaceAll("[/\\\\]", "_"));
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(bytes);
                }
                staged.add(FileProvider.getUriForFile(
                        MainActivity.this, getPackageName() + ".fileprovider", f));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "PDF 저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public void endShare() {
            // JS 스레드에서 호출되므로, UI 스레드로 넘기기 전에 목록을 복사해 둔다
            // (넘긴 뒤 beginShare 가 먼저 실행되면 비어 버린다)
            final ArrayList<Uri> list = new ArrayList<>(staged);
            runOnUiThread(() -> {
                if (list.isEmpty()) return;
                Intent i;
                if (list.size() == 1) {
                    i = new Intent(Intent.ACTION_SEND);
                    i.putExtra(Intent.EXTRA_STREAM, list.get(0));
                } else {
                    i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, list);
                }
                i.setType("application/pdf");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "PDF 보내기"));
            });
        }

        /** 웹 코드가 앱 안에서 돌고 있는지 확인용 */
        @JavascriptInterface
        public boolean available() {
            return true;
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
