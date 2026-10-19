package com.uguraltun.acad;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.InputStream;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/www/index.html";
    private static final int PICK_FILE = 1;
    private static final int PICK_PHOTO = 2;
    private Uri cameraUri;

    private WebView web;
    private WebViewAssetLoader loader;
    private ValueCallback<Uri[]> fileCallback;
    private Uri incoming;
    private String incomingName;
    private boolean pageReady = false;
    private int incomingSeq = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);

        loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/incoming/", path -> serveIncoming())
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException e) {
                    // nothing to open it with
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushIncoming();
            }
        });

        web.addJavascriptInterface(new Bridge(), "AcadNative");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                boolean wantsImage = false;
                if (params != null && params.getAcceptTypes() != null) {
                    for (String t : params.getAcceptTypes()) if (t != null && (t.equals("image/*") || t.equals("image/jpeg") || t.equals("image/png"))) wantsImage = true;
                }
                if (wantsImage) return openPhotoChooser();
                // DWG/DXF: open the system file manager (Files) directly
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra("android.provider.extra.SHOW_ADVANCED", true);
                i.putExtra("android.content.extra.SHOW_ADVANCED", true);
                try {
                    startActivityForResult(i, PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "Dosya seçici bulunamadı", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });

        handleIntent(getIntent());
        web.loadUrl(START);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        pushIncoming();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri == null) return;
        incoming = uri;
        incomingName = displayName(uri);
        incomingSeq++;
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception e) {
            // ignore
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "cizim.dwg";
        return name;
    }

    private WebResourceResponse serveIncoming() {
        if (incoming == null) return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
        try {
            InputStream is = getContentResolver().openInputStream(incoming);
            return new WebResourceResponse("application/octet-stream", null, is);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
        }
    }

    private void pushIncoming() {
        if (!pageReady || incoming == null || incomingName == null) return;
        String url = "/incoming/" + incomingSeq;
        String js = "(function t(n){if(window.__acad&&window.__acad.openUrl){window.__acad.openUrl("
                + JSONObject.quote(url) + "," + JSONObject.quote(incomingName)
                + ");}else if(n<50){setTimeout(function(){t(n+1)},100);}})(0)";
        incomingName = null;
        web.evaluateJavascript(js, null);
    }

    private boolean openPhotoChooser() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        Intent chooser = Intent.createChooser(pick, "Fotoğraf");
        cameraUri = null;
        try {
            File dir = new File(getCacheDir(), "camera");
            dir.mkdirs();
            File photo = new File(dir, "foto_" + System.currentTimeMillis() + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", photo);
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            cam.setClipData(ClipData.newRawUri("", cameraUri));
            cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
        } catch (Exception e) {
            cameraUri = null;
        }
        try {
            startActivityForResult(chooser, PICK_PHOTO);
            return true;
        } catch (ActivityNotFoundException e) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = null;
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != PICK_FILE && requestCode != PICK_PHOTO) || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) result = new Uri[]{data.getData()};
            else if (requestCode == PICK_PHOTO && cameraUri != null) result = new Uri[]{cameraUri};
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    private class Bridge {
        @JavascriptInterface
        public void shareFile(String name, String mime, String b64) {
            final byte[] bytes;
            try {
                bytes = Base64.decode(b64, Base64.DEFAULT);
            } catch (Exception e) {
                return;
            }
            runOnUiThread(() -> exportFile(name, mime, bytes, true));
        }

        @JavascriptInterface
        public void shareOnly(String name, String mime, String b64) {
            final byte[] bytes;
            try {
                bytes = Base64.decode(b64, Base64.DEFAULT);
            } catch (Exception e) {
                return;
            }
            runOnUiThread(() -> exportFile(name, mime, bytes, false));
        }
    }

    private void exportFile(String rawName, String mime, byte[] bytes, boolean toDownloads) {
        String name = rawName.replaceAll("[\\\\/:*?\"<>|]", "_");
        boolean saved = false;
        if (toDownloads && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Acad");
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(u)) {
                        if (os != null) { os.write(bytes); saved = true; }
                    }
                }
            } catch (Exception e) {
                saved = false;
            }
        }
        try {
            File dir = new File(getCacheDir(), "exports");
            dir.mkdirs();
            File f = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(bytes); }
            Uri share = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime);
            send.putExtra(Intent.EXTRA_STREAM, share);
            send.putExtra(Intent.EXTRA_SUBJECT, name);
            send.setClipData(ClipData.newRawUri("", share));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Paylaş"));
        } catch (Exception e) {
            // sharing not available
        }
        if (toDownloads) Toast.makeText(this, saved ? "İndirilenler › Acad klasörüne kaydedildi" : "PDF hazır", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__acad && window.__acad.back) ? window.__acad.back() : 'none'",
                v -> { if (!"\"closed\"".equals(v)) MainActivity.super.onBackPressed(); });
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
