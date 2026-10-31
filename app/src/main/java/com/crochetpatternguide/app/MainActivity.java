package com.crochetpatternguide.app;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
  private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";
  private static final String TAG = "CrochetApp";
  private static final String SHARED_PDF_DIR = "shared_pdfs";

  private WebView webView;
  private final EmailBridge emailBridge = new EmailBridge();
  @Nullable private AlertDialog emailAppDialog;
  @Nullable private ValueCallback<Uri[]> filePathCallback;

  @SuppressLint("SetJavaScriptEnabled")
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), true);

    WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
        .build();

    webView = new WebView(this);
    setContentView(webView);

    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(true);
    settings.setDatabaseEnabled(true);
    settings.setBuiltInZoomControls(false);
    settings.setLoadWithOverviewMode(true);
    settings.setUseWideViewPort(true);

    webView.addJavascriptInterface(emailBridge, "AndroidEmail");

    webView.setWebViewClient(new WebViewClient() {
      @Override
      public WebResourceResponse shouldInterceptRequest(
          @NonNull WebView view,
          @NonNull WebResourceRequest request
      ) {
        return assetLoader.shouldInterceptRequest(request.getUrl());
      }

      @Override
      public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WebResourceRequest request) {
        Uri uri = request.getUrl();
        return !"appassets.androidplatform.net".equals(uri.getHost());
      }
    });

    webView.setWebChromeClient(new WebChromeClient() {
      @Override
      public boolean onShowFileChooser(
          WebView view,
          ValueCallback<Uri[]> filePathCallback,
          FileChooserParams fileChooserParams
      ) {
        if (MainActivity.this.filePathCallback != null) {
          MainActivity.this.filePathCallback.onReceiveValue(null);
        }
        MainActivity.this.filePathCallback = filePathCallback;

        Intent intent = fileChooserParams.createIntent();
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        startActivityForResult(intent, 1001);
        return true;
      }
    });

    if (savedInstanceState == null) {
      webView.loadUrl(APP_URL);
    } else {
      webView.restoreState(savedInstanceState);
    }
  }

  @Override
  protected void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    webView.saveState(outState);
  }

  @Override
  public void onBackPressed() {
    if (webView.canGoBack()) {
      webView.goBack();
      return;
    }
    super.onBackPressed();
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != 1001 || filePathCallback == null) {
      return;
    }

    Uri[] results = null;
    if (resultCode == RESULT_OK && data != null && data.getData() != null) {
      results = new Uri[] { data.getData() };
    }

    filePathCallback.onReceiveValue(results);
    filePathCallback = null;
  }

  /**
   * Receives a PDF from the web app in base64 chunks (to avoid one huge string
   * crossing the bridge), stores it in the app cache and hands it to an email
   * app through a FileProvider URI. Methods run on the WebView's bridge thread.
   */
  private class EmailBridge {
    @Nullable private File pendingFile;
    @Nullable private FileOutputStream pendingStream;

    @JavascriptInterface
    public synchronized boolean begin(String fileName) {
      closePending();
      try {
        File dir = new File(getCacheDir(), SHARED_PDF_DIR);
        pendingFile = SharedPdfCache.prepare(dir, sanitizeFileName(fileName),
            System.currentTimeMillis());
        pendingStream = new FileOutputStream(pendingFile);
        return true;
      } catch (IOException e) {
        Log.e(TAG, "Could not create PDF for email", e);
        closePending();
        return false;
      }
    }

    @JavascriptInterface
    public synchronized boolean append(String base64Chunk) {
      if (pendingStream == null) {
        return false;
      }
      try {
        pendingStream.write(Base64.decode(base64Chunk, Base64.DEFAULT));
        return true;
      } catch (IOException | IllegalArgumentException e) {
        Log.e(TAG, "Could not write PDF chunk", e);
        closePending();
        return false;
      }
    }

    @JavascriptInterface
    public synchronized boolean send(String recipient, String subject, String body) {
      if (pendingStream == null || pendingFile == null || pendingFile.length() == 0) {
        closePending();
        return false;
      }
      File file = pendingFile;
      try {
        pendingStream.close();
      } catch (IOException e) {
        Log.e(TAG, "Could not finish PDF for email", e);
        closePending();
        return false;
      }
      pendingStream = null;
      pendingFile = null;

      Uri uri;
      try {
        uri = FileProvider.getUriForFile(
            MainActivity.this, getPackageName() + ".fileprovider", file);
      } catch (IllegalArgumentException e) {
        Log.e(TAG, "Could not expose PDF through FileProvider", e);
        file.delete();
        file.getParentFile().delete();
        return false;
      }

      runOnUiThread(() -> launchEmail(uri, recipient, subject, body));
      return true;
    }

    @JavascriptInterface
    public synchronized void cancel() {
      closePending();
    }

    private void closePending() {
      if (pendingStream != null) {
        try {
          pendingStream.close();
        } catch (IOException ignored) {
          // Nothing else to do; the file is discarded below.
        }
      }
      if (pendingFile != null) {
        //noinspection ResultOfMethodCallIgnored
        pendingFile.delete();
        pendingFile.getParentFile().delete();
      }
      pendingStream = null;
      pendingFile = null;
    }
  }

  private static String sanitizeFileName(String name) {
    String base = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
    if (base.isEmpty()) {
      base = "Pattern";
    }
    if (base.length() > 80) {
      base = base.substring(0, 80);
    }
    return base.toLowerCase(Locale.ROOT).endsWith(".pdf") ? base : base + ".pdf";
  }

  private void launchEmail(Uri pdfUri, String recipient, String subject, String body) {
    if (isFinishing() || isDestroyed()) return;
    Intent send = new Intent(Intent.ACTION_SEND);
    send.setType("application/pdf");
    if (recipient != null && !recipient.isEmpty()) {
      send.putExtra(Intent.EXTRA_EMAIL, new String[] { recipient });
    }
    send.putExtra(Intent.EXTRA_SUBJECT, subject);
    send.putExtra(Intent.EXTRA_TEXT, body);
    send.putExtra(Intent.EXTRA_STREAM, pdfUri);
    send.setClipData(ClipData.newRawUri("", pdfUri));
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

    // Prefer email apps only: those that handle mailto: and also accept a PDF.
    // Target a matching SEND activity explicitly; SENDTO doesn't carry attachments
    // reliably, and a mailto selector can resolve to an incompatible activity.
    List<Intent> emailIntents = new ArrayList<>();
    List<CharSequence> emailLabels = new ArrayList<>();
    List<ResolveInfo> shareTargets = new ArrayList<>();
    try {
      PackageManager pm = getPackageManager();
      // Query once, inside the guard, and reuse for the generic fallback.
      shareTargets = pm.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY);
      Set<String> emailPackages = new HashSet<>();
      for (ResolveInfo info : pm.queryIntentActivities(
          new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), PackageManager.MATCH_DEFAULT_ONLY)) {
        emailPackages.add(info.activityInfo.packageName);
      }
      for (ResolveInfo info : shareTargets) {
        if (info.activityInfo.exported && emailPackages.remove(info.activityInfo.packageName)) {
          Intent target = new Intent(send);
          target.setClassName(info.activityInfo.packageName, info.activityInfo.name);
          emailIntents.add(target);
          emailLabels.add(info.loadLabel(pm));
        }
      }
    } catch (RuntimeException e) {
      Log.w(TAG, "Could not query email apps", e);
      emailIntents.clear();
      emailLabels.clear();
    }

    if (isFinishing() || isDestroyed()) return;
    if (emailIntents.size() == 1) {
      startEmailActivity(emailIntents.get(0));
      return;
    }
    if (emailIntents.size() > 1) {
      // EXTRA_INITIAL_INTENTS is limited on newer Android versions. A local
      // picker keeps every installed mail app available, with no duplicate rows.
      emailAppDialog = new AlertDialog.Builder(this)
          .setTitle("Send pattern by email")
          .setItems(emailLabels.toArray(new CharSequence[0]),
              (dialog, which) -> startEmailActivity(emailIntents.get(which)))
          .setOnCancelListener(dialog -> reportEmailResult("cancelled"))
          .create();
      emailAppDialog.setOnDismissListener(dialog -> emailAppDialog = null);
      emailAppDialog.show();
      return;
    }

    // No dedicated email app: let the user pick any app that can send a PDF.
    if (shareTargets.isEmpty()) {
      reportEmailResult("error");
      return;
    }
    startEmailActivity(Intent.createChooser(send, "Send pattern by email"));
  }

  private void startEmailActivity(Intent intent) {
    try {
      startActivity(intent);
      reportEmailResult("opened");
    } catch (ActivityNotFoundException | SecurityException e) {
      Log.e(TAG, "No app can send the PDF", e);
      reportEmailResult("error");
    }
  }

  private void reportEmailResult(String result) {
    if (!isDestroyed()) {
      webView.evaluateJavascript(
          "window.onAndroidEmailResult && window.onAndroidEmailResult('" + result + "')", null);
    }
  }

  @Override
  protected void onDestroy() {
    if (emailAppDialog != null) {
      // A configuration change destroys this Activity and its WebView. Close
      // the window without delivering a cancellation to the disappearing page.
      emailAppDialog.setOnCancelListener(null);
      emailAppDialog.dismiss();
      emailAppDialog = null;
    }
    if (webView != null) {
      webView.removeJavascriptInterface("AndroidEmail");
      emailBridge.cancel();
      webView.destroy();
    }
    super.onDestroy();
  }
}
