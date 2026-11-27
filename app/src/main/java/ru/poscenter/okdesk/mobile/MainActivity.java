package ru.poscenter.okdesk.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Message;
import android.text.InputType;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.graphics.drawable.GradientDrawable;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.core.util.Consumer;
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.DisplayFeature;
import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowInfoTracker;
import androidx.window.layout.WindowLayoutInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String DEFAULT_PORTAL_URL = "https://shtrih-m.okdesk.ru/issues";
    private static final String PORTAL_URL_KEY = "okdesk_portal_start_url";
    private static final String OPTPOS_ORIGIN = "https://optposcenter.ru";
    private static final String OPTPOS_URL = OPTPOS_ORIGIN + "/catalog/";
    private static final String FS_ORIGIN = "https://fs.poscenter.org";
    private static final String FS_URL = FS_ORIGIN + "/#/";
    private static final int FILE_REQUEST = 1001;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private SecureStore store;
    private FrameLayout rootView;
    private LinearLayout pageContainer;
    private boolean firstRunShown;
    private FirstRunSetup firstRunSetup;
    private String lastMobileUrl = DEFAULT_PORTAL_URL;
    private volatile String currentPageUrl = DEFAULT_PORTAL_URL;
    private LinearLayout appBar;
    private TextView appBarTitle;
    private TextView mobileViewButton;
    private FrameLayout drawerLayer;
    private boolean drawerOpen;
    private OnBackInvokedCallback backInvokedCallback;
    private TextView appBarAction;
    private LinearLayout drawerPanel;
    private Spinner themeSelector;
    private WindowInfoTrackerCallbackAdapter foldTracker;
    private WindowLayoutInfo foldLayoutInfo;
    private final Consumer<WindowLayoutInfo> foldListener = info -> {
        foldLayoutInfo = info;
        publishFoldGeometry();
    };

    @Override
    @SuppressLint("RequiresFeature")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(27, 81, 96));
        getWindow().setNavigationBarColor(Color.rgb(233, 239, 237));
        store = new SecureStore(this);
        lastMobileUrl = portalStartUrl();
        currentPageUrl = lastMobileUrl;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            TextView error = new TextView(this);
            error.setText(R.string.webview_update_required);
            error.setTextSize(18);
            error.setPadding(32, 48, 32, 32);
            setContentView(error);
            return;
        }

        webView = new WebView(this);
        webView.setBackgroundColor(isDarkTheme() ? Color.rgb(16, 25, 28) : Color.WHITE);
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        rootView = new FrameLayout(this);
        pageContainer = new LinearLayout(this);
        pageContainer.setOrientation(LinearLayout.VERTICAL);
        rootView.addView(pageContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageContainer.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        createNavigationUi();
        rootView.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (drawerPanel == null || right - left == oldRight - oldLeft) return;
            ViewGroup.LayoutParams params = drawerPanel.getLayoutParams();
            params.width = Math.min(dp(340), Math.max(0, right - left - dp(48)));
            drawerPanel.setLayoutParams(params);
            publishFoldGeometry();
        });
        webView.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> publishFoldGeometry());
        rootView.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, 0, 0, 0);
            return insets;
        });
        setContentView(rootView);
        rootView.post(this::hideSystemBars);
        applyNativeTheme();
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        try {
            WebViewCompat.addWebMessageListener(webView, "OkdeskNative",
                    new java.util.HashSet<>(Arrays.asList(portalOrigin(), OPTPOS_ORIGIN)),
                    (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        if (!isMainFrame || !isScriptOrigin(sourceOrigin)
                                || message.getType() != WebMessageCompat.TYPE_STRING) return;
                        handleMessage(message.getData(), sourceOrigin, replyProxy);
                    });
            WebViewCompat.addDocumentStartJavaScript(webView,
                    InjectedScript.build(this, store.snapshot(), portalHost()), Collections.singleton(portalOrigin()));
            WebViewCompat.addDocumentStartJavaScript(webView,
                    OptposInjectedScript.build(this, store.snapshot()), Collections.singleton(OPTPOS_ORIGIN));
            WebViewCompat.addDocumentStartJavaScript(webView, buildFilesScript(), Collections.singleton(FS_ORIGIN));
        } catch (Exception error) {
            showError("Не удалось запустить мобильный скрипт: " + error.getMessage());
            return;
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String pageUrl = currentPageUrl;
                for (java.util.Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                    if ("referer".equalsIgnoreCase(header.getKey())
                            && hasOrigin(Uri.parse(header.getValue()), "fs.poscenter.org")) {
                        pageUrl = header.getValue();
                        break;
                    }
                }
                if (isKasperskyFastLoadResource(pageUrl, request.getUrl())) {
                    Log.i("OkdeskMobile", "Blocked Kaspersky resource on FS: " + request.getUrl());
                    String mime = request.getUrl().getLastPathSegment().toLowerCase().endsWith(".css")
                            ? "text/css" : "application/javascript";
                    return new WebResourceResponse(mime, "UTF-8",
                            new java.io.ByteArrayInputStream(new byte[0]));
                }
                Uri resource = request.getUrl();
                String resourceHost = resource.getHost();
                if (resourceHost != null && resourceHost.toLowerCase(java.util.Locale.ROOT).endsWith(".kaspersky-labs.com")) {
                    Log.i("OkdeskMobile", "Kaspersky resource allowed: page=" + pageUrl + " url=" + resource);
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                currentPageUrl = url;
                Uri page = Uri.parse(url);
                if (isOkdeskOrigin(page) && !page.getQueryParameterNames().contains("okdesk_desktop")) {
                    lastMobileUrl = url;
                }
                updateAppBar(page, false);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Uri page = Uri.parse(url);
                publishFoldGeometry();
                updateAppBar(page, isOkdeskOrigin(page) && page.getQueryParameterNames().contains("okdesk_desktop"));
                if (isOkdeskOrigin(page) && !page.getQueryParameterNames().contains("okdesk_desktop")) {
                    view.evaluateJavascript("!!document.getElementById('okdesk-mobile-app')", result -> {
                        if (view == webView && view.getUrl() != null && view.getUrl().equals(url)) {
                            updateAppBar(page, !"true".equals(result));
                        }
                    });
                }
                if (!firstRunShown && isTrustedOrigin(page)
                        && page.getPath() != null && page.getPath().startsWith("/issues")) {
                    try {
                        if (!store.snapshot().optBoolean(setupKey(), false)) {
                            firstRunShown = true;
                            showApiSetup();
                        }
                    } catch (Exception error) {
                        showError("Не удалось открыть первичную настройку: " + error.getMessage());
                    }
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (isAppOrigin(request.getUrl())) return false;
                openExternal(request.getUrl());
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                view.destroy();
                webView = null;
                recreate();
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQUEST);
                    return true;
                } catch (Exception error) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "Не удалось открыть выбор файла", Toast.LENGTH_LONG).show();
                    return false;
                }
            }

            @Override
            public boolean onCreateWindow(WebView parent, boolean isDialog, boolean isUserGesture,
                    Message resultMsg) {
                if (!isUserGesture) return false;
                WebView child = new WebView(MainActivity.this);
                child.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        Uri uri = request.getUrl();
                        String path = uri.getPath();
                        // Preview links must not replace the file catalog in the main WebView.
                        boolean fsFile = hasOrigin(uri, "fs.poscenter.org") && path != null
                                && !path.equals("/") && !path.equals("/index.html");
                        if (fsFile) openExternal(uri);
                        else if (isAppOrigin(uri)) parent.loadUrl(uri.toString());
                        else openExternal(request.getUrl());
                        view.destroy();
                        return true;
                    }

                    @Override
                    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                        view.destroy();
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(child);
                resultMsg.sendToTarget();
                return true;
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            try {
                Uri uri = Uri.parse(url);
                if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("HTTPS required");
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                        .replace('/', '_').replace('\\', '_');
                DownloadManager.Request request = new DownloadManager.Request(uri);
                request.setTitle(name);
                request.setMimeType(mimeType);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                request.addRequestHeader("User-Agent", userAgent);
                if (isAppOrigin(uri)) {
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) request.addRequestHeader("Cookie", cookie);
                }
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                Toast.makeText(this, "Загрузка: " + name, Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(this, "Не удалось скачать файл: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        if (hasPortalStartUrl()) webView.loadUrl(portalStartUrl());
        else showPortalSettings(true);
        if (Build.VERSION.SDK_INT >= 33) {
            backInvokedCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback);
        }
    }

    private void createNavigationUi() {
        appBar = new LinearLayout(this);
        appBar.setGravity(Gravity.CENTER_VERTICAL);
        appBar.setOrientation(LinearLayout.HORIZONTAL);
        appBar.setPadding(dp(8), 0, dp(12), 0);
        appBar.setBackgroundColor(Color.rgb(21, 94, 100));
        appBar.setElevation(dp(4));
        appBar.setVisibility(View.GONE);

        TextView menuButton = new TextView(this);
        menuButton.setText("☰");
        menuButton.setTextSize(22);
        menuButton.setTextColor(Color.WHITE);
        menuButton.setGravity(Gravity.CENTER);
        appBar.addView(menuButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        menuButton.setOnClickListener(view -> showDrawer());

        mobileViewButton = new TextView(this);
        mobileViewButton.setText("Мобильный вид");
        mobileViewButton.setTextSize(13);
        mobileViewButton.setTextColor(Color.WHITE);
        mobileViewButton.setGravity(Gravity.CENTER);
        mobileViewButton.setPadding(dp(10), 0, dp(10), 0);
        mobileViewButton.setContentDescription("Вернуться к мобильному виду заявки");
        mobileViewButton.setBackgroundColor(0x33FFFFFF);
        mobileViewButton.setVisibility(View.GONE);
        appBar.addView(mobileViewButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        mobileViewButton.setOnClickListener(view -> returnToMobileView());

        appBarTitle = new TextView(this);
        appBarTitle.setTextColor(Color.WHITE);
        appBarTitle.setTextSize(16);
        appBarTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = dp(8);
        appBar.addView(appBarTitle, titleParams);

        appBarAction = new TextView(this);
        appBarAction.setText("⚙");
        appBarAction.setTextSize(21);
        appBarAction.setTextColor(Color.WHITE);
        appBarAction.setGravity(Gravity.CENTER);
        appBarAction.setContentDescription("Настройки каталога");
        appBarAction.setVisibility(View.GONE);
        appBar.addView(appBarAction, new LinearLayout.LayoutParams(dp(44), dp(48)));
        appBarAction.setOnClickListener(view -> {
            if (isOptposOrigin(Uri.parse(webView.getUrl()))) openOptposSettings();
            else showApiSetup();
        });
        pageContainer.addView(appBar, 0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        drawerLayer = new FrameLayout(this);
        drawerLayer.setVisibility(View.GONE);
        View scrim = new View(this);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(view -> hideDrawer());
        drawerLayer.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        drawerPanel = panel;
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(38), dp(18), dp(18));
        panel.setBackgroundColor(Color.rgb(22, 31, 35));
        panel.setElevation(dp(12));
        int drawerWidth = Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(48));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        drawerLayer.addView(panel, panelParams);

        addDrawerItem(panel, "Заявки", () -> openSection(lastMobileUrl));
        addDrawerItem(panel, "optPOScenter", () -> openSection(OPTPOS_URL));
        addDrawerItem(panel, "FS POScenter", () -> openSection(FS_URL));
        View spacer = new View(this);
        panel.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView themeLabel = new TextView(this);
        themeLabel.setText("Тема приложения");
        themeLabel.setTextSize(13);
        themeLabel.setPadding(dp(12), 0, dp(12), dp(6));
        panel.addView(themeLabel);
        themeSelector = new Spinner(this);
        ArrayAdapter<String> themeAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item,
                new String[] {"Тёмная", "Светлая"}) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return styleThemeOption(super.getView(position, convertView, parent), false);
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return styleThemeOption(super.getDropDownView(position, convertView, parent), true);
            }
        };
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        themeSelector.setAdapter(themeAdapter);
        themeSelector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            private boolean initialized;
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (!initialized) { initialized = true; return; }
                setAppTheme(position == 0 ? "dark" : "light");
            }
        });
        LinearLayout.LayoutParams themeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        themeParams.bottomMargin = dp(4);
        panel.addView(themeSelector, themeParams);
        rootView.addView(drawerLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyNativeTheme();
    }

    private void addDrawerItem(LinearLayout panel, String title, Runnable action) {
        TextView item = new TextView(this);
        item.setText(title);
        item.setTextColor(Color.WHITE);
        item.setTextSize(18);
        item.setLineSpacing(dp(4), 1f);
        item.setPadding(dp(12), dp(17), dp(12), dp(17));
        item.setBackgroundColor(Color.TRANSPARENT);
        panel.addView(item, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        item.setOnClickListener(view -> { hideDrawer(); action.run(); });
    }

    private void updateAppBar(Uri page, boolean desktopMode) {
        boolean isOkdesk = isOkdeskOrigin(page);
        boolean visible = !isOkdesk || desktopMode;
        appBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        mobileViewButton.setVisibility(isOkdesk && desktopMode ? View.VISIBLE : View.GONE);
        appBarTitle.setText(isOkdesk
                ? (hasOkdeskApiToken() ? "Okdesk" : "Okdesk · без API")
                : isOptposOrigin(page) ? "optPOScenter" : "FS POScenter");
        boolean needsApiSetup = isOkdesk && !hasOkdeskApiToken();
        appBarAction.setText(isOptposOrigin(page) ? "⚙" : "⚿");
        appBarAction.setVisibility(isOptposOrigin(page) || needsApiSetup ? View.VISIBLE : View.GONE);
    }

    private void returnToMobileView() {
        if (webView == null || webView.getUrl() == null) return;
        try {
            store.put("okdesk_mobile_view_" + portalHost(), "on");
            Uri current = Uri.parse(webView.getUrl());
            Uri.Builder target = current.buildUpon().clearQuery();
            for (String name : current.getQueryParameterNames()) {
                if ("okdesk_desktop".equals(name)) continue;
                for (String value : current.getQueryParameters(name)) target.appendQueryParameter(name, value);
            }
            String targetUrl = target.build().toString();
            webView.evaluateJavascript("sessionStorage.setItem('okdesk_app_pending_mobile_mode','on');location.replace("
                    + JSONObject.quote(targetUrl) + ")", null);
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось вернуться в мобильный вид: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showDrawer() {
        drawerOpen = true;
        drawerLayer.setVisibility(View.VISIBLE);
    }

    private void hideDrawer() {
        drawerOpen = false;
        drawerLayer.setVisibility(View.GONE);
    }

    private void openSection(String url) {
        if (webView == null) return;
        webView.loadUrl(url);
    }

    private View styleThemeOption(View view, boolean dropdown) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            boolean dark = isDarkTheme();
            text.setTextColor(dark ? Color.rgb(237, 245, 243) : Color.rgb(32, 48, 50));
            text.setPadding(dp(12), dp(dropdown ? 12 : 8), dp(12), dp(dropdown ? 12 : 8));
            text.setBackgroundColor(dark ? Color.rgb(36, 53, 58) : Color.rgb(250, 252, 252));
        }
        return view;
    }

    private void setAppTheme(String theme) {
        try {
            store.put("okdesk_app_theme", theme);
            applyNativeTheme();
            if (webView != null) {
                webView.evaluateJavascript("document.documentElement.dataset.okdeskAppTheme='" + theme
                        + "';document.documentElement.dataset.okdeskScriptTheme='" + theme + "';", null);
            }
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось сохранить тему: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean isDarkTheme() {
        try { return !"light".equalsIgnoreCase(store.snapshot().optString("okdesk_app_theme", "dark")); }
        catch (Exception ignored) { return true; }
    }

    private void applyNativeTheme() {
        boolean dark = isDarkTheme();
        int surface = dark ? Color.rgb(22, 31, 35) : Color.rgb(250, 252, 252);
        int text = dark ? Color.rgb(237, 245, 243) : Color.rgb(32, 48, 50);
        if (rootView != null) rootView.setBackgroundColor(dark ? Color.rgb(16, 25, 28) : Color.WHITE);
        if (drawerPanel != null) drawerPanel.setBackgroundColor(surface);
        if (themeSelector != null) {
            themeSelector.setSelection(dark ? 0 : 1, false);
            GradientDrawable background = new GradientDrawable();
            background.setColor(dark ? Color.rgb(36, 53, 58) : Color.rgb(250, 252, 252));
            background.setCornerRadius(dp(8));
            background.setStroke(dp(1), dark ? Color.rgb(58, 80, 81) : Color.rgb(197, 213, 210));
            themeSelector.setBackground(background);
            themeSelector.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(surface));
            if (themeSelector.getAdapter() != null) ((ArrayAdapter<?>) themeSelector.getAdapter()).notifyDataSetChanged();
        }
        if (drawerPanel != null) {
            for (int i = 0; i < drawerPanel.getChildCount(); i++) {
                View child = drawerPanel.getChildAt(i);
                if (child instanceof TextView) {
                    ((TextView) child).setTextColor(text);
                }
            }
        }
        if (appBar != null) appBar.setBackgroundColor(dark ? Color.rgb(21, 94, 100) : Color.rgb(28, 112, 117));
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void openOptposSettings() {
        if (webView == null || !isOptposOrigin(Uri.parse(webView.getUrl()))) {
            openSection(OPTPOS_URL);
            return;
        }
        webView.evaluateJavascript("(function(){let attempts=0;const open=()=>{if(typeof window.__okdeskOpenOptposSettings==='function'){window.__okdeskOpenOptposSettings();return}if(window.__okdeskInvokeOptposCommand){window.__okdeskInvokeOptposCommand('Настройки быстрого каталога');return}if(++attempts<50){setTimeout(open,100);return}if(window.OkdeskNative)window.OkdeskNative.postMessage(JSON.stringify({type:'toast',message:'Настройки каталога не загрузились. Обновите страницу каталога и повторите.'}))};open()})()", null);
    }

    private void showApiSetup() {
        if (firstRunSetup != null) firstRunSetup.close();
        firstRunSetup = new FirstRunSetup(this, rootView, store, () -> {
            CookieManager.getInstance().flush();
            recreate();
        }, portalHost(), portalOrigin());
        firstRunSetup.show();
    }

    private void showPortalSettings() {
        showPortalSettings(false);
    }

    private void showPortalSettings(boolean initialSetup) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://portal.example.ru/issues");
        input.setText(portalStartUrl());
        input.setSelection(input.length());
        new AlertDialog.Builder(this)
                .setTitle(initialSetup ? "Настройка Okdesk" : "Портал Okdesk")
                .setMessage(initialSetup
                        ? "Укажите адрес портала Okdesk для входа. Приложение будет открывать его при запуске."
                        : "Укажите адрес страницы, которую приложение будет открывать после запуска и выхода из полной карточки.")
                .setView(input)
                .setNegativeButton(initialSetup ? "Выход" : "Отмена", (dialog, which) -> {
                    if (initialSetup) finish();
                })
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    try {
                        String url = normalizePortalStartUrl(input.getText().toString());
                        store.put(PORTAL_URL_KEY, url);
                        firstRunShown = false;
                        recreate();
                    } catch (Exception error) {
                        Toast.makeText(this, "Адрес портала некорректен: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).setCancelable(!initialSetup).show();
    }

    private String portalStartUrl() {
        try {
            return normalizePortalStartUrl(store.snapshot().optString(PORTAL_URL_KEY, DEFAULT_PORTAL_URL));
        } catch (Exception ignored) {
            return DEFAULT_PORTAL_URL;
        }
    }

    private boolean hasPortalStartUrl() {
        try { return store.snapshot().has(PORTAL_URL_KEY); }
        catch (Exception ignored) { return false; }
    }

    private static String normalizePortalStartUrl(String raw) throws Exception {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("введите адрес");
        if (!value.contains("://")) value = "https://" + value;
        Uri uri = Uri.parse(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("нужен HTTPS-адрес без логина, пароля и нестандартного порта");
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) path = "/issues";
        return new Uri.Builder().scheme("https").authority(uri.getHost())
                .path(path).encodedQuery(uri.getEncodedQuery()).build().toString();
    }

    private String portalHost() {
        return Uri.parse(portalStartUrl()).getHost();
    }

    private String portalOrigin() {
        return "https://" + portalHost();
    }

    private String setupKey() {
        return "okdesk_mobile_setup_complete_" + portalHost();
    }

    private boolean hasOkdeskApiToken() {
        try { return !store.snapshot().optString("okdesk_api_token_" + portalHost(), "").trim().isEmpty(); }
        catch (Exception ignored) { return false; }
    }

    private String buildFilesScript() throws Exception {
        String source = readAsset("poscenter-files.user.js", 500_000);
        String theme = store.snapshot().optString("okdesk_app_theme", "dark");
        String themeCss = "html[data-okdesk-app-theme=dark]{color-scheme:dark;--bg:#10191c;--surface:#1b282c;--surface-2:#24353a;--surface-3:#2a3a3e;--text:#edf5f3;--muted:#a6bbb8;--border:#3a5051;--accent:#75cdbc;--accent-hover:#93dfcf;--shadow:0 12px 32px rgba(0,0,0,.28)}html[data-okdesk-app-theme=light]{color-scheme:light}html[data-okdesk-app-theme=dark] body{background:#10191c!important;color:#edf5f3!important}html[data-okdesk-app-theme=dark] .poscenter-folder-name:hover,html[data-okdesk-app-theme=dark] .poscenter-folder-name:focus-visible{background:#2a3a3e!important;outline-color:#75cdbc!important}html[data-okdesk-app-theme=dark] .poscenter-download-folder,html[data-okdesk-app-theme=dark] .poscenter-copy-folder-link,html[data-okdesk-app-theme=dark] #poscenter-folder-progress,html[data-okdesk-app-theme=dark] .poscenter-progress-actions button,html[data-okdesk-app-theme=dark] button[data-open-prefix],html[data-okdesk-app-theme=dark] .actions button{background:#1b282c!important;color:#edf5f3!important;border-color:#3a5051!important}html[data-okdesk-app-theme=dark] button[data-open-prefix]:hover,html[data-okdesk-app-theme=dark] .actions button:hover{background:#2a3a3e!important}html[data-okdesk-app-theme=dark] .poscenter-progress-text{color:#a6bbb8!important}html[data-okdesk-app-theme=dark] .poscenter-progress-track{background:#2a3a3e!important}@media(max-width:860px){.container{max-width:none!important;padding:10px!important}.hero{padding:16px!important}h1{font-size:24px!important}.toolbar{padding:12px!important}.panel{margin-top:10px!important}.row{padding:12px!important}}";
        return "(function(){if(window.top!==window)return;const theme=" + JSONObject.quote(theme) + ";const source=" + JSONObject.quote(source) + ";const css=" + JSONObject.quote(themeCss) + ";const boot=()=>{if(window.__poscenterFilesInjected)return;if(!document.documentElement||!document.head){setTimeout(boot,50);return}document.documentElement.dataset.okdeskAppTheme=theme;try{(0,eval)(source);const style=document.createElement('style');style.id='okdesk-files-app-theme';style.textContent=css;document.head.appendChild(style);window.__poscenterFilesInjected=true}catch(error){console.error('FS Poscenter add-on failed',error);setTimeout(boot,500)}};if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',boot,{once:true});else boot();window.addEventListener('pageshow',boot)})();";
    }

    private String readAsset(String name, int maxSize) throws Exception {
        try (InputStream input = getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maxSize) throw new IllegalStateException("Asset is too large: " + name);
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void handleMessage(String raw, Uri sourceOrigin, JavaScriptReplyProxy replyProxy) {
        try {
            JSONObject message = new JSONObject(raw);
            switch (message.optString("type")) {
                case "set":
                    store.put(message.optString("key"), message.opt("value"));
                    break;
                case "delete":
                    store.remove(message.optString("key"));
                    break;
                case "clipboard":
                    String text = message.optString("text");
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText("Okdesk", text));
                    break;
                case "request":
                    if (isOptposOrigin(sourceOrigin) && !isAllowedOptposRequest(message.optString("url"))) {
                        throw new IllegalArgumentException("Запрос заблокирован для этого раздела.");
                    }
                    io.execute(() -> fetchText(message, replyProxy));
                    break;
                case "navigation":
                    runOnUiThread(this::showDrawer);
                    break;
                case "portal-settings":
                    runOnUiThread(this::showPortalSettings);
                    break;
                case "toast":
                    String toastText = message.optString("message", "");
                    if (!toastText.isEmpty()) runOnUiThread(() ->
                            Toast.makeText(this, toastText, Toast.LENGTH_LONG).show());
                    break;
                default:
                    break;
            }
        } catch (Exception error) {
            Toast.makeText(this, "Ошибка сохранения настроек: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint("RequiresFeature")
    private void fetchText(JSONObject message, JavaScriptReplyProxy replyProxy) {
        int id = message.optInt("id");
        JSONObject reply = new JSONObject();
        HttpURLConnection connection = null;
        try {
            reply.put("id", id);
            if (!"GET".equalsIgnoreCase(message.optString("method"))) throw new IllegalArgumentException("Only GET is allowed");
            URL url = new URL(message.getString("url"));
            for (int redirect = 0; redirect < 4; redirect++) {
                requirePublicHttps(url);
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String target = connection.getHeaderField("Location");
                    if (target == null) throw new IllegalStateException("Redirect without location");
                    url = new URL(url, target);
                    connection.disconnect(); connection = null;
                    continue;
                }
                reply.put("status", status);
                try (InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while (input != null && (n = input.read(buffer)) != -1) {
                        if (output.size() + n > 2_000_000) throw new IllegalStateException("File is too large");
                        output.write(buffer, 0, n);
                    }
                    reply.put("text", output.toString(StandardCharsets.UTF_8.name()));
                }
                runOnUiThread(() -> replyProxy.postMessage(reply.toString()));
                return;
            }
            throw new IllegalStateException("Too many redirects");
        } catch (Exception error) {
            try {
                reply.put("id", id);
                reply.put("error", error.getMessage());
            } catch (Exception ignored) { }
            runOnUiThread(() -> replyProxy.postMessage(reply.toString()));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void requirePublicHttps(URL url) throws Exception {
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getPort() != -1 && url.getPort() != 443)
            throw new IllegalArgumentException("Only public HTTPS is allowed");
        for (InetAddress address : InetAddress.getAllByName(url.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                    || address.isMulticastAddress()) throw new IllegalArgumentException("Private host is blocked");
        }
    }

    private boolean isTrustedOrigin(Uri uri) {
        return isOkdeskOrigin(uri);
    }

    private boolean isOkdeskOrigin(Uri uri) {
        return hasOrigin(uri, portalHost());
    }

    private static boolean isOptposOrigin(Uri uri) {
        return hasOrigin(uri, "optposcenter.ru");
    }

    private static boolean hasOrigin(Uri uri, String host) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && host.equalsIgnoreCase(uri.getHost()) && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private boolean isScriptOrigin(Uri uri) {
        return isOkdeskOrigin(uri) || isOptposOrigin(uri);
    }

    private boolean isAppOrigin(Uri uri) {
        return isScriptOrigin(uri) || hasOrigin(uri, "fs.poscenter.org");
    }

    private static boolean isAllowedOptposRequest(String url) {
        Uri uri = Uri.parse(url);
        return hasOrigin(uri, "youtube.com") || hasOrigin(uri, "www.youtube.com")
                || hasOrigin(uri, "rutube.ru");
    }

    private static boolean isKasperskyFastLoadResource(String pageUrl, Uri resource) {
        String host = resource == null ? null : resource.getHost();
        if (pageUrl == null || resource == null
                || !hasOrigin(Uri.parse(pageUrl), "fs.poscenter.org")
                || host == null || !host.toLowerCase(java.util.Locale.ROOT).endsWith(".kaspersky-labs.com")) return false;
        String path = resource.getPath();
        if (path == null) return false;
        String normalized = path.toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith("/main.js") || normalized.endsWith("/main.css");
    }

    private void openExternal(Uri uri) {
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (Exception error) { Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show(); }
    }

    private void showError(String text) {
        TextView error = new TextView(this);
        error.setText(text);
        error.setTextSize(18);
        error.setPadding(32, 48, 32, 32);
        setContentView(error);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQUEST && fileCallback != null) {
            Uri[] picked = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(picked);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (drawerOpen) { hideDrawer(); return; }
        WebView current = webView;
        if (current == null) { finish(); return; }
        String startUrl = portalStartUrl();
        Uri currentUri = Uri.parse(current.getUrl() == null ? startUrl : current.getUrl());
        if (isOkdeskOrigin(currentUri) && currentUri.getQueryParameterNames().contains("okdesk_desktop")) {
            current.loadUrl(lastMobileUrl);
            return;
        }
        current.evaluateJavascript("(function(){var app=document.getElementById('okdesk-mobile-app');if(!app)return 'not-mobile';var sheet=app.querySelector('.m-sheet-backdrop .m-sheet-close');if(sheet){sheet.click();return 'handled'}var back=app.querySelector('.m-top [data-action=back]');if(back&&!back.hidden){back.click();return 'handled'}return 'mobile-root'})()", result -> {
            if (webView != current || "\"handled\"".equals(result)) return;
            Uri page = Uri.parse(current.getUrl() == null ? startUrl : current.getUrl());
            if ("\"mobile-root\"".equals(result)) { finish(); return; }
            if (isOkdeskOrigin(page) && page.getQueryParameterNames().contains("okdesk_desktop")) {
                current.loadUrl(lastMobileUrl);
            } else if (!isOkdeskOrigin(page)) {
                current.loadUrl(lastMobileUrl);
            } else if (current.canGoBack()) {
                current.goBack();
            } else if (!startUrl.equals(page.toString())) {
                current.loadUrl(startUrl);
            } else {
                finish();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (webView == null) return;
        try {
            if (foldTracker == null) foldTracker = new WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(this));
            foldTracker.addWindowLayoutInfoListener(this, getMainExecutor(), foldListener);
        } catch (RuntimeException | LinkageError error) {
            Log.w("OkdeskMobile", "Fold detection unavailable; using responsive layout", error);
            foldLayoutInfo = null;
            publishFoldGeometry();
        }
    }

    @Override
    protected void onStop() {
        try {
            if (foldTracker != null) foldTracker.removeWindowLayoutInfoListener(foldListener);
        } catch (RuntimeException | LinkageError error) {
            Log.w("OkdeskMobile", "Unable to stop fold listener", error);
        }
        super.onStop();
    }

    private void publishFoldGeometry() {
        if (webView == null || webView.getUrl() == null || !isOkdeskOrigin(Uri.parse(webView.getUrl()))
                || webView.getWidth() <= 0 || webView.getHeight() <= 0) return;
        try {
            JSONObject geometry = new JSONObject().put("orientation", "none");
            if (foldLayoutInfo != null) {
                int[] location = new int[2];
                webView.getLocationInWindow(location);
                Rect webBounds = new Rect(location[0], location[1], location[0] + webView.getWidth(), location[1] + webView.getHeight());
                for (DisplayFeature feature : foldLayoutInfo.getDisplayFeatures()) {
                    if (!(feature instanceof FoldingFeature)) continue;
                    FoldingFeature fold = (FoldingFeature) feature;
                    if (!fold.isSeparating() && !FoldingFeature.State.HALF_OPENED.equals(fold.getState())
                            && !FoldingFeature.OcclusionType.FULL.equals(fold.getOcclusionType())) continue;
                    Rect bounds = fold.getBounds();
                    // A flexible fold can be a zero-width or zero-height line.
                    if (bounds.right < webBounds.left || bounds.left > webBounds.right
                            || bounds.bottom < webBounds.top || bounds.top > webBounds.bottom) continue;
                    geometry.put("orientation", FoldingFeature.Orientation.HORIZONTAL.equals(fold.getOrientation()) ? "horizontal" : "vertical")
                        .put("left", bounds.left - location[0]).put("top", bounds.top - location[1])
                        .put("right", bounds.right - location[0]).put("bottom", bounds.bottom - location[1])
                        .put("width", webView.getWidth()).put("height", webView.getHeight());
                    break;
                }
            }
            webView.evaluateJavascript("window.__okdeskFold=" + geometry + ";window.dispatchEvent(new Event('okdesk-fold-change'));", null);
        } catch (RuntimeException | org.json.JSONException error) {
            Log.w("OkdeskMobile", "Unable to update fold geometry", error);
        }
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
        }
        if (firstRunSetup != null) firstRunSetup.close();
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        io.shutdownNow();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
