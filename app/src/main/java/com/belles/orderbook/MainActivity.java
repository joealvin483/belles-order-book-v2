package com.belles.orderbook;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import java.io.File;
import java.io.OutputStream;

/**
 * Belle's Dress Shop — Android shell.
 *
 * The whole order book is one HTML file in app/src/main/assets. It is served
 * over https://appassets.androidplatform.net rather than file:// so that:
 *   - saved orders live in a normal, stable origin that survives app updates
 *   - requests to the Google Apps Script are not blocked as cross-origin
 *
 * Everything a browser would do through a download prompt, the share sheet or
 * window.print() is handled natively and exposed to the page as AndroidBridge.
 */
public class MainActivity extends AppCompatActivity {

    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String START_URL = ORIGIN + "/assets/index.html";
    private static final int REQ_FILE = 1001;
    private static final int REQ_CAMERA_PERM = 1002;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraOutput;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);              // this is where the orders are kept
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClientCompat() {
            @Override
            public WebResourceResponse shouldInterceptRequest(@NonNull WebView view,
                                                             @NonNull WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(@NonNull WebView view,
                                                    @NonNull WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.startsWith(ORIGIN)) return false;
                openExternally(url);               // receipts, mailto:, sms:, whatsapp, viber
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // grants the page's getUserMedia() the camera, for the QR scanner
            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                runOnUiThread(() -> {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                    } else {
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
                        request.deny();
                    }
                });
            }

            // makes <input type="file" capture="environment"> work for form scanning
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                boolean wantsCamera = params.isCaptureEnabled();
                if (wantsCamera && !hasCamera()) {
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    return true;
                }
                launchPicker(wantsCamera);
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        if (state == null) web.loadUrl(START_URL);
        else web.restoreState(state);
    }

    /* ---------------- what the page can ask the phone to do ---------------- */

    private class Bridge {

        /** Writes a backup or CSV straight into Downloads, no prompt. */
        @JavascriptInterface
        public boolean saveFile(String name, String base64, String mime) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime == null ? "text/plain" : mime);
                    Uri uri = getContentResolver()
                            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return false;
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out == null) return false;
                    out.write(bytes);
                    out.close();
                } else {
                    File dir = Environment
                            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dir, name));
                    out.write(bytes);
                    out.close();
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Hands a receipt to Messages, WhatsApp, Viber, Telegram or email. */
        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> openExternally(url));
        }

        /**
         * Sends the receipt text to one named app, falling back to the system
         * share sheet. This is the only way Messenger can be handed a
         * pre-filled message — its web links cannot carry text.
         */
        @JavascriptInterface
        public void shareTo(String pkg, String text) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                send.putExtra(Intent.EXTRA_SUBJECT, "Belle's Dress Shop receipt");

                if (pkg != null && !pkg.isEmpty()) {
                    Intent direct = new Intent(send);
                    direct.setPackage(pkg);
                    if (direct.resolveActivity(getPackageManager()) != null) {
                        try {
                            startActivity(direct);
                            return;
                        } catch (Exception ignored) { }
                    }
                    Toast.makeText(MainActivity.this,
                            "That app is not installed — pick another",
                            Toast.LENGTH_SHORT).show();
                }
                try {
                    startActivity(Intent.createChooser(send, "Send receipt"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Nothing on this tablet can send that",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void copyText(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Receipt", text));
            });
        }

        /** Sends the page to Android's print service, honouring the print CSS. */
        @JavascriptInterface
        public void printPage() {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                if (pm == null) return;
                String job = "Belle's Dress Shop";
                pm.print(job,
                        web.createPrintDocumentAdapter(job),
                        new PrintAttributes.Builder().build());
            });
        }

        @JavascriptInterface
        public String platform() {
            return "android";
        }
    }

    private void openExternally(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "No app on this tablet can open that", Toast.LENGTH_SHORT).show();
        }
    }

    /* ---------------- camera and gallery for form scanning ---------------- */

    private boolean hasCamera() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void launchPicker(boolean wantsCamera) {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");

        Intent chooser = Intent.createChooser(pick, "Photograph the form");

        if (wantsCamera) {
            try {
                File dir = new File(getCacheDir(), "scans");
                if (!dir.exists()) dir.mkdirs();
                File shot = new File(dir, "form_" + System.currentTimeMillis() + ".jpg");
                cameraOutput = FileProvider.getUriForFile(
                        this, getPackageName() + ".files", shot);
                Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutput);
                cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
            } catch (Exception e) {
                cameraOutput = null;
            }
        }
        startActivityForResult(chooser, REQ_FILE);
    }

    @Override
    protected void onActivityResult(int req, int result, @Nullable Intent data) {
        if (req != REQ_FILE) {
            super.onActivityResult(req, result, data);
            return;
        }
        if (fileCallback == null) return;

        Uri[] out = null;
        if (result == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) out = new Uri[]{data.getData()};
            else if (cameraOutput != null) out = new Uri[]{cameraOutput};
        }
        fileCallback.onReceiveValue(out);
        fileCallback = null;
        cameraOutput = null;
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_CAMERA_PERM) {
            Toast.makeText(this,
                    hasCamera() ? "Camera allowed — tap Take a photo again"
                                : "Without the camera you can still choose an image",
                    Toast.LENGTH_LONG).show();
        }
    }

    /* ---------------- housekeeping ---------------- */

    private long lastBackPress = 0;

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    /**
     * Back closes whatever is open, in order: receipt panel, scanner, then an
     * open order. Only when the page says there is nothing left to close does a
     * second press exit — a stray tap should never shut the order book mid-sale.
     */
    @Override
    public boolean onKeyDown(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_BACK) {
            web.evaluateJavascript(
                "(function(){" +
                "var p=document.getElementById('receiptPanel');" +
                "if(p&&p.dataset.open==='1'){p.dataset.open='0';p.innerHTML='';return 'closed';}" +
                "var s=document.getElementById('scanPanel');" +
                "if(s&&s.innerHTML){s.innerHTML='';return 'closed';}" +
                "var q=document.getElementById('qrScanHost');" +
                "if(q&&q.style.display!=='none'){if(window.stopScanner)stopScanner();return 'closed';}" +
                "if(window.detailOpen){backToList();return 'closed';}" +
                "return 'none';})()",
                value -> {
                    if (value != null && value.contains("closed")) return;
                    if (web.canGoBack()) { web.goBack(); return; }
                    long now = System.currentTimeMillis();
                    if (now - lastBackPress < 2000) {
                        finish();
                    } else {
                        lastBackPress = now;
                        Toast.makeText(MainActivity.this,
                                "Press back again to close the order book",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            return true;
        }
        return super.onKeyDown(code, event);
    }
}
