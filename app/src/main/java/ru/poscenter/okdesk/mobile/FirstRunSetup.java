package ru.poscenter.okdesk.mobile;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class FirstRunSetup {
    private final Activity activity;
    private final FrameLayout root;
    private final SecureStore store;
    private final Runnable complete;
    private final String host;
    private final String origin;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Group> groups = new ArrayList<>();
    private FrameLayout overlay;
    private EditText tokenInput;
    private Button checkButton;
    private Button continueButton;
    private Button withoutApiButton;
    private Spinner groupSpinner;
    private ProgressBar progress;
    private TextView feedback;
    private TextView verifiedLabel;
    private String verifiedToken;

    FirstRunSetup(Activity activity, FrameLayout root, SecureStore store, Runnable complete, String host, String origin) {
        this.activity = activity;
        this.root = root;
        this.store = store;
        this.complete = complete;
        this.host = host;
        this.origin = origin;
    }

    void show() {
        boolean dark = isDarkTheme();
        int background = dark ? Color.rgb(16, 25, 28) : Color.rgb(241, 246, 246);
        int primaryText = dark ? Color.rgb(237, 245, 243) : Color.rgb(31, 54, 57);
        int secondaryText = dark ? Color.rgb(166, 187, 184) : Color.rgb(79, 101, 104);
        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(background);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        overlay.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(32), dp(24), dp(32));
        scroll.addView(form);

        TextView title = text("Настройка Okdesk", 24, primaryText);
        title.setTypeface(null, Typeface.BOLD);
        form.addView(title);
        TextView intro = text("Войдите в Okdesk. Для мобильного списка заявок и действий через API укажите токен и выберите рабочую группу. Можно продолжить без токена и пользоваться сайтом и другими разделами приложения.",
                15, secondaryText);
        intro.setPadding(0, dp(12), 0, dp(24));
        form.addView(intro);

        form.addView(text("API-токен", 14, primaryText));
        tokenInput = new EditText(activity);
        tokenInput.setSingleLine(true);
        tokenInput.setTextSize(16);
        tokenInput.setHint("Вставьте токен Okdesk");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setTextColor(primaryText);
        tokenInput.setHintTextColor(secondaryText);
        tokenInput.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                dark ? Color.rgb(76, 101, 103) : Color.rgb(165, 184, 186)));
        try { tokenInput.setText(store.snapshot().optString("okdesk_api_token_" + host, "")); }
        catch (Exception ignored) { }
        form.addView(tokenInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        checkButton = new Button(activity);
        checkButton.setText("Проверить токен");
        checkButton.setOnClickListener(view -> verifyToken());
        form.addView(checkButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        progress = new ProgressBar(activity);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        progressParams.topMargin = dp(14);
        form.addView(progress, progressParams);

        feedback = text("", 14, dark ? Color.rgb(255, 172, 161) : Color.rgb(158, 48, 48));
        feedback.setPadding(0, dp(12), 0, dp(12));
        form.addView(feedback);

        verifiedLabel = text("", 14, dark ? Color.rgb(136, 215, 194) : Color.rgb(44, 102, 73));
        verifiedLabel.setVisibility(View.GONE);
        form.addView(verifiedLabel);

        groupSpinner = new Spinner(activity);
        groupSpinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        groupParams.topMargin = dp(18);
        form.addView(groupSpinner, groupParams);

        continueButton = new Button(activity);
        continueButton.setText("Сохранить и открыть заявки");
        continueButton.setEnabled(false);
        continueButton.setVisibility(View.GONE);
        continueButton.setOnClickListener(view -> save());
        LinearLayout.LayoutParams continueParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        continueParams.topMargin = dp(12);
        form.addView(continueButton, continueParams);

        withoutApiButton = new Button(activity);
        withoutApiButton.setText("Продолжить без API-токена");
        withoutApiButton.setOnClickListener(view -> continueWithoutApi());
        LinearLayout.LayoutParams withoutApiParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        withoutApiParams.topMargin = dp(8);
        form.addView(withoutApiButton, withoutApiParams);
        updateWithoutApiVisibility();

        TextView note = text("Токен можно добавить позже через меню приложения → «Настройка API Okdesk». Шаблоны из Tampermonkey переносятся через экспорт и импорт JSON.",
                13, secondaryText);
        note.setPadding(0, dp(28), 0, 0);
        form.addView(note);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tokenInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (verifiedToken != null && !verifiedToken.equals(s.toString().trim())) {
                    verifiedToken = null;
                    continueButton.setEnabled(false);
                    continueButton.setVisibility(View.GONE);
                    groupSpinner.setVisibility(View.GONE);
                    verifiedLabel.setVisibility(View.GONE);
                }
                updateWithoutApiVisibility();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void updateWithoutApiVisibility() {
        if (withoutApiButton != null) {
            withoutApiButton.setVisibility(tokenInput.getText().toString().trim().isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private boolean isDarkTheme() {
        try { return !"light".equalsIgnoreCase(store.snapshot().optString("okdesk_app_theme", "dark")); }
        catch (Exception ignored) { return true; }
    }

    private void continueWithoutApi() {
        try {
            store.put("okdesk_api_token_" + host, "");
            store.put("okdesk_mobile_group_id_" + host, 0);
            store.put("okdesk_mobile_view_" + host, "on");
            store.put("okdesk_mobile_api_enabled_" + host, false);
            store.put("okdesk_mobile_setup_complete_" + host, true);
            close();
            complete.run();
        } catch (Exception error) {
            feedback.setText("Не удалось сохранить режим без API: " + error.getMessage());
        }
    }

    private void verifyToken() {
        String token = tokenInput.getText().toString().trim();
        if (token.isEmpty()) { feedback.setText("Введите API-токен."); return; }
        checkButton.setEnabled(false);
        continueButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        feedback.setText("");
        String cookie = CookieManager.getInstance().getCookie(origin);
        io.execute(() -> {
            try {
                JSONArray result = request("GET", "/employees/groups", token, cookie, null).getJSONArray("items");
                JSONObject owner = request("POST", "/login_link", token, cookie,
                        new JSONObject().put("expire_after", 1).put("one_time", true).toString());
                int ownerId = owner.optInt("user_id");
                if (ownerId <= 0 || !"employee".equals(owner.optString("user_type"))) {
                    throw new IllegalStateException("Токен не принадлежит активному сотруднику Okdesk.");
                }
                List<Group> found = new ArrayList<>();
                for (int index = 0; index < result.length(); index++) {
                    JSONObject item = result.optJSONObject(index);
                    if (item != null && item.optInt("id") > 0 && item.optBoolean("active", true)) {
                        found.add(new Group(item.optInt("id"), item.optString("name", "Группа " + item.optInt("id"))));
                    }
                }
                if (found.isEmpty()) throw new IllegalStateException("Для аккаунта не найдены доступные группы.");
                Collator collator = Collator.getInstance(new Locale("ru"));
                Collections.sort(found, (left, right) -> collator.compare(left.name, right.name));
                activity.runOnUiThread(() -> {
                    if (overlay == null || !overlay.isAttachedToWindow()) return;
                    groups.clear(); groups.addAll(found);
                    List<String> labels = new ArrayList<>();
                    labels.add("Выберите рабочую группу");
                    for (Group group : groups) labels.add(group.name + " (ID " + group.id + ")");
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                            android.R.layout.simple_spinner_item, labels);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    groupSpinner.setAdapter(adapter);
                    groupSpinner.setVisibility(View.VISIBLE);
                    verifiedToken = token;
                    verifiedLabel.setText("Токен подтверждён, сотрудник ID " + ownerId);
                    verifiedLabel.setVisibility(View.VISIBLE);
                    continueButton.setEnabled(true);
                    continueButton.setVisibility(View.VISIBLE);
                    checkButton.setEnabled(true);
                    progress.setVisibility(View.GONE);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (overlay == null || !overlay.isAttachedToWindow()) return;
                    feedback.setText(error.getMessage());
                    checkButton.setEnabled(true);
                    progress.setVisibility(View.GONE);
                });
            }
        });
    }

    private void save() {
        int index = groupSpinner.getSelectedItemPosition();
        if (verifiedToken == null) { feedback.setText("Сначала проверьте токен."); return; }
        if (index < 1 || index > groups.size()) { feedback.setText("Выберите рабочую группу."); return; }
        Group group = groups.get(index - 1);
        String token = verifiedToken;
        String cookie = CookieManager.getInstance().getCookie(origin);
        continueButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        feedback.setText("Проверяю доступ к заявкам...");
        io.execute(() -> {
            try {
                request("GET", "/issues/list?page%5Bnumber%5D=1&page%5Bsize%5D=1&assignee_group_ids%5B%5D=" + group.id,
                        token, cookie, null);
                store.put("okdesk_api_token_" + host, token);
                store.put("okdesk_mobile_group_id_" + host, group.id);
                store.put("transfer_settings_" + host, new JSONObject()
                        .put("authorId", JSONObject.NULL)
                        .put("authorMode", "auto")
                        .put("groupId", group.id)
                        .put("employees", new JSONArray()).toString());
                store.put("okdesk_mobile_active_tab_" + host, "group");
                store.put("okdesk_mobile_view_" + host, "on");
                store.put("okdesk_mobile_api_enabled_" + host, true);
                store.put("okdesk_mobile_setup_complete_" + host, true);
                activity.runOnUiThread(() -> {
                    if (overlay == null || !overlay.isAttachedToWindow()) return;
                    close();
                    complete.run();
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (overlay == null || !overlay.isAttachedToWindow()) return;
                    feedback.setText("Не удалось завершить настройку: " + error.getMessage());
                    continueButton.setEnabled(true);
                    progress.setVisibility(View.GONE);
                });
            }
        });
    }

    private JSONObject request(String method, String path, String token, String cookie, String body) throws Exception {
        String url = origin + "/api/v1" + path + (path.contains("?") ? "&" : "?") + "api_token=" + Uri.encode(token);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (cookie != null) connection.setRequestProperty("Cookie", cookie);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            int status = connection.getResponseCode();
            if (status == 401) throw new IllegalStateException("HTTP 401: API не принял токен. Проверьте токен этого аккаунта и вход в Okdesk.");
            if (status == 403) throw new IllegalStateException("HTTP 403: у аккаунта нет доступа к этому действию API.");
            if (status < 200 || status >= 300) throw new IllegalStateException("Ошибка Okdesk API: HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 2_000_000) throw new IllegalStateException("Ответ API слишком большой.");
                    output.write(buffer, 0, count);
                }
                String json = output.toString(StandardCharsets.UTF_8.name()).trim();
                if (json.startsWith("[")) return new JSONObject().put("items", new JSONArray(json));
                return new JSONObject(json);
            }
        } finally {
            connection.disconnect();
        }
    }

    void close() {
        io.shutdownNow();
        if (overlay != null) overlay.removeAllViews();
        if (overlay != null) root.removeView(overlay);
        overlay = null;
    }

    private TextView text(String value, int size, int color) {
        TextView result = new TextView(activity);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        return result;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class Group {
        final int id;
        final String name;
        Group(int id, String name) { this.id = id; this.name = name; }
    }
}
