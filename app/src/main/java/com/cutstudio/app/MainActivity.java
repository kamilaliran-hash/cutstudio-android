package com.cutstudio.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewAssetLoader;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CutStudio for Android: hosts the editor (assets/index.html) in a WebView,
 * served from an https origin so recording, audio mixing and blob URLs all work.
 * Native pieces: the media picker, saving exports to the gallery, and the back button.
 */
public class MainActivity extends Activity {

    private static final int PICK_FILES = 1001;
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    private final Map<String, OutputStream> streams = new ConcurrentHashMap<>();
    private final Map<String, File> tempFiles = new ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#15171C"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0C0D10"));
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        setupEdgeToEdge(root);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u)); // external links open in the browser
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;

                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                List<String> types = new ArrayList<>();
                String[] accept = params.getAcceptTypes();
                if (accept != null) {
                    for (String a : accept) {
                        if (a == null) continue;
                        for (String t : a.split(",")) {
                            t = t.trim();
                            if (!t.isEmpty() && t.contains("/")) types.add(t);
                        }
                    }
                }
                if (!types.isEmpty()) pick.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(pick, "Add media"), PICK_FILES);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
        }

        web.loadUrl(HOME);
    }

    /** Draw behind the system bars on every Android version and pad the content by the bar sizes. */
    @SuppressWarnings("deprecation")
    private void setupEdgeToEdge(View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_FILES) {
            if (fileCallback == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int k = 0; k < n; k++) result[k] = data.getClipData().getItemAt(k).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** Back closes open panels first; leaving with unsaved work asks for confirmation. */
    @Override
    @SuppressWarnings({"deprecation", "MissingSuperCall"})
    public void onBackPressed() {
        web.evaluateJavascript(
                "(window.handleBack&&window.handleBack())?'1':((window.hasWork&&window.hasWork())?'2':'0')",
                value -> {
                    if (value != null && value.contains("1")) return;
                    if (value != null && value.contains("2")) {
                        new AlertDialog.Builder(this)
                                .setTitle("Leave CutStudio?")
                                .setMessage("Your edit isn't saved. Export it first if you want to keep it.")
                                .setPositiveButton("Leave", (d, w) -> finish())
                                .setNegativeButton("Stay", null)
                                .show();
                    } else {
                        finish();
                    }
                });
    }

    @Override
    protected void onDestroy() {
        for (OutputStream o : streams.values()) {
            try { o.close(); } catch (IOException ignored) { }
        }
        for (File f : tempFiles.values()) f.delete();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Called from the page (window.AndroidBridge) to save exported videos and frames. */
    private class Bridge {
        @JavascriptInterface
        public String begin(String name) {
            try {
                String id = UUID.randomUUID().toString();
                File f = new File(getCacheDir(), "export-" + id);
                streams.put(id, new BufferedOutputStream(new FileOutputStream(f), 1 << 20));
                tempFiles.put(id, f);
                return id;
            } catch (IOException e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean append(String id, String base64) {
            OutputStream o = streams.get(id);
            if (o == null) return false;
            try {
                o.write(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String finish(String id, String name, String mime) {
            OutputStream o = streams.remove(id);
            File f = tempFiles.remove(id);
            if (o == null || f == null) return "ERR: nothing to save";
            try {
                o.close();
                return publish(f, name, mime == null || mime.isEmpty() ? "video/mp4" : mime);
            } catch (Exception e) {
                return "ERR: " + e.getMessage();
            } finally {
                f.delete();
            }
        }
    }

    /** Copies a finished file into Movies/CutStudio (videos) or Pictures/CutStudio (images). */
    @SuppressWarnings("deprecation")
    private String publish(File src, String name, String mime) throws IOException {
        boolean image = mime.startsWith("image/");
        String dirName = image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES;

        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, dirName + "/CutStudio");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = image
                    ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = cr.insert(collection, v);
            if (item == null) throw new IOException("could not create the file");
            try (OutputStream out = cr.openOutputStream(item); InputStream in = new FileInputStream(src)) {
                if (out == null) throw new IOException("could not open the file");
                copy(in, out);
            } catch (IOException e) {
                cr.delete(item, null, null);
                throw e;
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, v, null, null);
            return dirName + "/CutStudio/" + name;
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(dirName), "CutStudio");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("storage permission is needed");
        File dst = new File(dir, name);
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int n = 1; dst.exists(); n++) dst = new File(dir, base + " (" + n + ")" + ext);
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            copy(in, out);
        }
        MediaScannerConnection.scanFile(this, new String[]{dst.getAbsolutePath()}, new String[]{mime}, null);
        return dirName + "/CutStudio/" + dst.getName();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }
}
