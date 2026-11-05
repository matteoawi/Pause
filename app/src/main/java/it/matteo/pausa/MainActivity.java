package it.matteo.pausa;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.*;
import android.util.TypedValue;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import it.matteo.pausa.core.BlockStatistics;

/** A short public flow, with optional statistics and separate technical tools. */
public final class MainActivity extends Activity {
    private enum Page { HOME, SETUP, BACKGROUND, SETTINGS, PREFERENCES, HELP, APPS, APP_STATS, PRIVACY, ADVANCED, RULES, RECENT, ABOUT, LICENSES }
    private int PAPER, INK, MUTED, GREEN, LINE, ERROR, SURFACE, ON_PRIMARY;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final ArrayDeque<Page> history = new ArrayDeque<>();
    private final Map<String, String> appLabels = new HashMap<>();
    private Settings prefs;
    private Page page = Page.HOME;
    private LinearLayout content, appSummary, appStatsList;
    private TextView stateTitle, stateBody, inlineMessage, diagnostics, totalBlocks, todayBlocks;
    private WeeklyChart chart;
    private Button primary;
    private ValueAnimator counterAnimation;
    private long displayedTotal = -1, renderedStatsTotal = -1;
    private String serviceState = "", searchQuery = "";
    private int actionError, domains, renderGeneration;
    private boolean loadingDomains, requestingPermission, updateWasBusy;
    private long activationRequested;
    private List<AppEntry> cachedApps;
    private android.window.OnBackInvokedCallback backCallback;
    private boolean backRegistered;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            String current = PausaVpnService.status;
            if (current.equals("on")) prefs.putText("setup_complete", "yes");
            if (page == Page.HOME) { refreshHome(); refreshStats(); }
            if (page == Page.APP_STATS) refreshAppStats();
            if (page == Page.ADVANCED) refreshDiagnostics();
            if (page == Page.SETTINGS && updateWasBusy != ProtectionUpdates.busy.get()) build();
            if (page == Page.APPS && !current.equals(serviceState)) build();
            serviceState = current; handler.postDelayed(this, 800);
        }
    };
    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(AppPreferences.wrap(base)); }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); prefs = new Settings(this);
        PAPER = getColor(R.color.pause_background); INK = getColor(R.color.pause_ink); MUTED = getColor(R.color.pause_muted);
        GREEN = getColor(R.color.pause_primary); LINE = getColor(R.color.pause_line); ERROR = getColor(R.color.pause_error);
        SURFACE = getColor(R.color.pause_surface); ON_PRIMARY = getColor(R.color.pause_on_primary);
        if (active()) startService(new Intent(this, PausaVpnService.class).setAction(PausaVpnService.REFRESH_UI));
        if (saved != null) {
            page = restorePage(saved.getString("page", "HOME")); searchQuery = saved.getString("search", ""); actionError = saved.getInt("error");
            ArrayList<String> stack = saved.getStringArrayList("history");
            if (stack != null) for (String entry : stack) history.addLast(restorePage(entry));
        }
        if (prefs.number("queries") > 0 || PausaVpnService.status.equals("on")) prefs.putText("setup_complete", "yes");
        if (saved == null && !prefs.text("setup_complete", "").equals("yes") && !prefs.text("intro_seen", "").equals("yes")) page = Page.SETUP;
        if (Build.VERSION.SDK_INT >= 33) backCallback = this::goBack;
        build();
    }
    @Override protected void onResume() {
        super.onResume(); if (page == Page.SETTINGS || page == Page.APPS) build();
        handler.removeCallbacks(refresh); handler.post(refresh);
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); if (counterAnimation != null) counterAnimation.cancel(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putString("page", page.name()); saved.putString("search", searchQuery); saved.putInt("error", actionError);
        ArrayList<String> stack = new ArrayList<>(); for (Page item : history) stack.add(item.name()); saved.putStringArrayList("history", stack);
        super.onSaveInstanceState(saved);
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null); background.shutdownNow(); if (counterAnimation != null) counterAnimation.cancel();
        if (Build.VERSION.SDK_INT >= 33 && backRegistered) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        super.onDestroy();
    }
    // Android 13+ uses the registered OnBackInvokedCallback; this is the Android 8–12 fallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { if (page == Page.HOME) super.onBackPressed(); else goBack(); }
    private boolean active() { return PausaVpnService.status.equals("on") || PausaVpnService.status.equals("starting"); }
    private String s(int id, Object... values) { return getString(id, values); }
    private static Page restorePage(String name) { try { return Page.valueOf(name); } catch (IllegalArgumentException e) { return Page.HOME; } }
    private String number(long value) { return java.text.NumberFormat.getIntegerInstance(AppPreferences.locale(this)).format(value); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void open(Page next) { history.addLast(page); page = next; actionError = 0; build(); }
    private void home() { history.clear(); page = Page.HOME; actionError = 0; build(); }
    private void goBack() { page = history.isEmpty() ? Page.HOME : history.removeLast(); actionError = 0; build(); }
    private void build() {
        int generation = ++renderGeneration;
        if (counterAnimation != null) counterAnimation.cancel();
        stateTitle = stateBody = inlineMessage = diagnostics = totalBlocks = todayBlocks = null;
        primary = null; chart = null; appSummary = appStatsList = null; displayedTotal = -1; renderedStatsTotal = -1;
        LinearLayout root = column(); root.setBackgroundColor(PAPER); root.setPadding(dp(24), dp(8), dp(24), dp(12));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(24) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                dp(24) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom()); return insets;
        });
        setContentView(root); root.requestApplyInsets(); LinearLayout header = row();
        if (page == Page.HOME || (page == Page.SETUP && history.isEmpty())) {
            ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.brand_symbol); logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(32), dp(32)); logoParams.rightMargin = dp(10); header.addView(logo, logoParams);
            header.addView(text(s(R.string.app_name), 26, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
            header.addView(button(R.string.settings, false, v -> open(Page.SETTINGS)));
        } else {
            header.addView(button(R.string.back, false, v -> goBack())); TextView brand = text(s(R.string.app_name), 18, MUTED, true); brand.setGravity(Gravity.END);
            header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(header); ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = column(); content.setPadding(0, dp(24), 0, dp(24)); scroll.addView(content);
        switch (page) {
            case HOME: protection(); break;
            case SETUP: setup(); break;
            case BACKGROUND: backgroundGuide(); break;
            case SETTINGS: settings(); break;
            case PREFERENCES: preferences(); break;
            case HELP: help(); break;
            case APPS: apps(generation); break;
            case APP_STATS: appStats(); break;
            case PRIVACY: privacy(); break;
            case ADVANCED: advanced(); break;
            case RULES: rules(); break;
            case RECENT: recent(); break;
            case ABOUT: about(); break;
            case LICENSES: licenses(); break;
        }
        serviceState = PausaVpnService.status;
        if (Build.VERSION.SDK_INT >= 33) {
            boolean intercept = page != Page.HOME;
            if (intercept && !backRegistered) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
            if (!intercept && backRegistered) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backRegistered = intercept;
        }
    }
    private void protection() {
        stateTitle = text("", 26, INK, true); stateTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(stateTitle, 4, 8);
        stateBody = text("", 16, MUTED, false); add(stateBody, 0, 16);
        primary = button(R.string.activate, true, v -> toggleProtection()); primary.setTextSize(17); primary.setMinHeight(dp(60)); add(primary, 0, 20);
        inlineMessage = text("", 15, ERROR, false); inlineMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(inlineMessage, 0, 12);
        LinearLayout stats = column(); stats.setPadding(dp(20), dp(18), dp(20), dp(16));
        GradientDrawable surface = new GradientDrawable(); surface.setColor(SURFACE); surface.setCornerRadius(dp(16)); stats.setBackground(surface);
        stats.addView(text(s(R.string.total_blocks), 15, MUTED, false)); totalBlocks = text("0", 40, GREEN, true); stats.addView(totalBlocks);
        todayBlocks = text("", 14, GREEN, false); stats.addView(todayBlocks);
        String historyCaption = s(R.string.last_week);
        try {
            java.time.LocalDate since = java.time.LocalDate.parse(prefs.text("statistics_since", java.time.LocalDate.now().toString()));
            if (since.isAfter(java.time.LocalDate.now().minusDays(6))) historyCaption = s(R.string.history_since, since.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", AppPreferences.locale(this))));
        } catch (java.time.format.DateTimeParseException ignored) { }
        TextView caption = text(historyCaption, 12, MUTED, false); LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(-1, -2); captionParams.topMargin = dp(18); stats.addView(caption, captionParams);
        chart = new WeeklyChart(this); stats.addView(chart, new LinearLayout.LayoutParams(-1, dp(76))); add(stats, 0, 8);
        add(text(s(R.string.blocks_note), 12, MUTED, false), 0, 20);
        LinearLayout appHeading = row(); appHeading.addView(text(s(R.string.from_apps), 20, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        appHeading.addView(button(R.string.all_apps, false, v -> open(Page.APP_STATS))); add(appHeading, 0, 4);
        appSummary = column(); content.addView(appSummary);
        add(text(s(R.string.attribution_note), 12, MUTED, false), 8, 18);
        actionRow(R.string.help_short, null, () -> open(Page.HELP));
        refreshHome(); refreshStats();
    }
    private void refreshHome() {
        if (stateTitle == null) return;
        String state = PausaVpnService.status; boolean on = state.equals("on");
        boolean starting = state.equals("starting") || (activationRequested > 0 && SystemClock.elapsedRealtime() - activationRequested < 5000 && state.equals("off"));
        setIfChanged(stateTitle, s(on ? R.string.home_on_title : starting ? R.string.home_starting_title : state.equals("error") ? R.string.home_error_title : R.string.home_off_title));
        stateTitle.setTextColor(on ? GREEN : INK);
        setIfChanged(stateBody, s(on ? R.string.home_on_body : starting ? R.string.home_starting_body : state.equals("error") ? R.string.home_error_body : R.string.home_off_body));
        int action = on ? R.string.pause : starting ? R.string.starting : R.string.activate;
        setIfChanged(primary, s(action)); buttonIcon(primary, action, true); primary.setEnabled(!starting);
        inlineMessage.setText(actionError == 0 ? "" : s(actionError)); inlineMessage.setVisibility(actionError == 0 ? View.GONE : View.VISIBLE);
    }
    private void refreshStats() {
        if (totalBlocks == null) return;
        BlockStatistics.Snapshot snapshot = StatisticsStore.snapshot(this);
        setIfChanged(todayBlocks, s(R.string.today_blocks, number(snapshot.today)));
        chart.setValues(snapshot.week);
        if (snapshot.total == renderedStatsTotal) return;
        renderedStatsTotal = snapshot.total;
        if (displayedTotal >= 0 && snapshot.total > displayedTotal && ValueAnimator.areAnimatorsEnabled() && !prefs.text("animations", "on").equals("off")) {
            if (counterAnimation != null) counterAnimation.cancel(); long from = displayedTotal;
            counterAnimation = ValueAnimator.ofFloat(0, 1); counterAnimation.setDuration(320);
            counterAnimation.setInterpolator(new android.view.animation.DecelerateInterpolator());
            counterAnimation.addUpdateListener(animation -> { displayedTotal = from + Math.round((snapshot.total - from) * (Float) animation.getAnimatedValue()); totalBlocks.setText(number(displayedTotal)); });
            counterAnimation.start();
        } else { displayedTotal = snapshot.total; totalBlocks.setText(number(snapshot.total)); }
        appSummary.removeAllViews(); int shown = 0;
        for (BlockStatistics.AppCount app : snapshot.apps) { if (shown++ == 3) break; addAppStat(appSummary, app, snapshot.total, true); }
        if (snapshot.apps.isEmpty()) appSummary.addView(text(s(R.string.apps_activity_empty), 15, MUTED, false));
    }
    private void appStats() {
        title(R.string.from_apps); paragraph(R.string.attribution_note);
        appStatsList = column(); content.addView(appStatsList); refreshAppStats();
        actionRow(R.string.excluded_apps, s(R.string.excluded_apps_summary), () -> open(Page.APPS));
    }
    private void refreshAppStats() {
        if (appStatsList == null) return;
        BlockStatistics.Snapshot snapshot = StatisticsStore.snapshot(this);
        if (renderedStatsTotal == snapshot.total) return;
        renderedStatsTotal = snapshot.total; appStatsList.removeAllViews();
        if (snapshot.apps.isEmpty()) appStatsList.addView(text(s(R.string.apps_activity_empty), 15, MUTED, false));
        for (BlockStatistics.AppCount app : snapshot.apps) addAppStat(appStatsList, app, snapshot.total, false);
    }
    private void addAppStat(LinearLayout parent, BlockStatistics.AppCount app, long total, boolean compact) {
        String label = appLabel(app.key, app.label); LinearLayout line = row(); line.setPadding(0, dp(10), 0, dp(10)); line.setMinimumHeight(dp(52));
        if (!app.key.equals(BlockStatistics.UNKNOWN)) {
            try {
                ImageView icon = new ImageView(this); icon.setImageDrawable(getPackageManager().getApplicationIcon(app.key));
                icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(32), dp(32)); params.rightMargin = dp(12); line.addView(icon, params);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        LinearLayout copy = column(); copy.addView(text(label, 15, INK, true));
        if (!compact) copy.addView(text(s(R.string.app_blocks, number(app.count)), 13, MUTED, false));
        line.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        if (compact) line.addView(text(number(app.count), 18, GREEN, true));
        parent.addView(line);
        if (!compact) {
            ProgressBar distribution = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); distribution.setMax(1000);
            distribution.setProgress((int) (1000.0 * app.count / Math.max(1, total))); distribution.setProgressTintList(ColorStateList.valueOf(GREEN));
            distribution.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
            distribution.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); parent.addView(distribution, new LinearLayout.LayoutParams(-1, dp(6)));
        }
    }
    private String appLabel(String key, String storedLabel) {
        if (key.equals(BlockStatistics.UNKNOWN)) return s(R.string.unknown_app);
        String label = appLabels.get(key); if (label != null) return label;
        try { label = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(key, 0)).toString(); }
        catch (PackageManager.NameNotFoundException e) { label = storedLabel.isEmpty() ? s(R.string.unknown_app) : storedLabel; }
        appLabels.put(key, label); return label;
    }
    private void setup() {
        title(R.string.setup_title); paragraph(R.string.setup_body); section(R.string.setup_local_title, R.string.setup_local_body); section(R.string.setup_vpn_title, R.string.setup_vpn_body);
        add(text(s(R.string.setup_dns_body), 14, MUTED, false), 20, 16);
        add(text(s(R.string.limit_short), 14, MUTED, false), 0, 12);
        actionRow(R.string.background_title, s(R.string.background_summary), () -> open(Page.BACKGROUND));
        if (actionError != 0) add(text(s(actionError), 15, ERROR, false), 0, 16);
        Button start = button(R.string.setup_start, true, v -> requestVpn()); start.setMinHeight(dp(60)); start.setEnabled(!requestingPermission); add(start, 8, 8);
        add(button(R.string.not_now, false, v -> { prefs.putText("intro_seen", "yes"); home(); }), 0, 12);
        add(button(R.string.privacy, false, v -> open(Page.PRIVACY)), 0, 0);
    }
    private void backgroundGuide() {
        title(R.string.background_title); paragraph(R.string.background_intro);
        section(R.string.background_battery_title, R.string.background_battery_body);
        add(button(R.string.background_open_settings, true, v -> appDetailsSettings()), 4, 12);
        String maker = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        String brand = Build.BRAND.toLowerCase(Locale.ROOT);
        if (maker.contains("samsung")) {
            section(R.string.background_samsung_title, R.string.background_samsung_body);
            add(button(R.string.background_samsung_settings, false, v -> samsungBackgroundSettings()), 4, 12);
        } else if (maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco") || brand.contains("redmi") || brand.contains("poco")) {
            section(R.string.background_xiaomi_title, R.string.background_xiaomi_body);
        } else if (maker.equals("google")) {
            section(R.string.background_pixel_title, R.string.background_pixel_body);
        }
        section(R.string.background_clean_title, R.string.background_clean_body);
        section(R.string.background_check_title, R.string.background_check_body);
        add(button(R.string.initial_guide, false, v -> open(Page.SETUP)), 4, 12);
    }
    private void appDetailsSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException | SecurityException e) { toast(R.string.background_settings_unavailable); }
    }
    private void samsungBackgroundSettings() {
        try {
            startActivity(new Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
                .setPackage("com.samsung.android.lool").putExtra("activity_type", 2));
        } catch (ActivityNotFoundException | SecurityException e) { appDetailsSettings(); }
    }
    private void toggleProtection() {
        actionError = 0;
        if (PausaVpnService.status.equals("on")) { activationRequested = 0; pauseProtection(); refreshHome(); return; }
        try { if (VpnService.prepare(this) != null) open(Page.SETUP); else startProtection(); }
        catch (RuntimeException e) { actionError = R.string.permission_unavailable; refreshHome(); }
    }
    private void pauseProtection() { startService(new Intent(this, PausaVpnService.class).setAction(PausaVpnService.STOP)); }
    private void requestVpn() {
        if (requestingPermission) return;
        try {
            Intent consent = VpnService.prepare(this);
            if (consent == null) startProtection(); else { requestingPermission = true; startActivityForResult(consent, 10); }
        } catch (RuntimeException e) { requestingPermission = false; actionError = R.string.permission_unavailable; build(); }
    }
    private void startProtection() {
        requestingPermission = false; prefs.putText("intro_seen", "yes");
        try {
            activationRequested = SystemClock.elapsedRealtime(); startForegroundService(new Intent(this, PausaVpnService.class).setAction(PausaVpnService.START)); home();
        } catch (RuntimeException e) { activationRequested = 0; actionError = R.string.activation_failed; build(); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 10) { requestingPermission = false; if (result == RESULT_OK) startProtection(); else { actionError = R.string.permission_cancelled; build(); } }
    }
    private void settings() {
        title(R.string.settings); actionRow(R.string.personalize, s(R.string.personalize_summary), () -> open(Page.PREFERENCES));
        actionRow(R.string.excluded_apps, s(R.string.excluded_apps_summary), () -> open(Page.APPS));
        updateWasBusy = ProtectionUpdates.busy.get(); String updated = prefs.text("updated", "");
        try {
            String timestamp = prefs.text("updated_at", "");
            if (!timestamp.isEmpty()) updated = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, AppPreferences.locale(this)).format(new Date(Long.parseLong(timestamp)));
        } catch (NumberFormatException ignored) { }
        String updateDescription = updateWasBusy ? s(R.string.updating) : updated.isEmpty() ? s(R.string.update_summary) : s(R.string.updated_on, updated);
        View update = actionRow(R.string.update_protection, updateDescription, () -> { ProtectionUpdates.start(this); build(); }); update.setEnabled(!updateWasBusy);
        if (ProtectionUpdates.result != 0) add(text(s(ProtectionUpdates.result), 14, ProtectionUpdates.result == R.string.update_failed ? ERROR : GREEN, false), 8, 16);
        boolean notifications = getSystemService(NotificationManager.class).areNotificationsEnabled();
        actionRow(R.string.notifications, s(notifications ? R.string.notifications_on : R.string.notifications_off), this::notificationSettings);
        actionRow(R.string.background_title, s(R.string.background_summary), () -> open(Page.BACKGROUND));
        actionRow(R.string.help, s(R.string.help_summary), () -> open(Page.HELP)); actionRow(R.string.privacy, s(R.string.privacy_summary), () -> open(Page.PRIVACY));
        actionRow(R.string.advanced, s(R.string.advanced_summary), () -> open(Page.ADVANCED)); actionRow(R.string.about, s(R.string.version, BuildConfig.VERSION_NAME), () -> open(Page.ABOUT));
        actionRow(R.string.share_app, s(R.string.share_summary), this::shareApk);
    }
    private void preferences() {
        title(R.string.personalize);
        preferenceChoice(R.string.language, "language", new String[]{"system", "it", "en", "es"},
            new int[]{R.string.language_system, R.string.language_it, R.string.language_en, R.string.language_es});
        preferenceChoice(R.string.appearance, "appearance", new String[]{"system", "light", "dark"},
            new int[]{R.string.theme_system, R.string.theme_light, R.string.theme_dark});
        Switch motion = new Switch(this); motion.setText(R.string.animations); motion.setTextColor(INK); motion.setTextSize(16); motion.setMinHeight(dp(56));
        motion.setChecked(!prefs.text("animations", "on").equals("off"));
        motion.setOnCheckedChangeListener((view, checked) -> prefs.putText("animations", checked ? "on" : "off"));
        add(motion, 24, 8); paragraph(R.string.animations_summary);
    }
    private void preferenceChoice(int label, String key, String[] values, int[] labels) {
        TextView heading = text(s(label), 20, INK, true); add(heading, 20, 8);
        Spinner options = new Spinner(this); String[] names = new String[labels.length]; int selected = 0;
        for (int i = 0; i < labels.length; i++) { names[i] = s(labels[i]); if (values[i].equals(key.equals("language") ? AppPreferences.language(this) : prefs.text(key, "system"))) selected = i; }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); options.setAdapter(adapter); options.setSelection(selected);
        options.setMinimumHeight(dp(52)); options.setContentDescription(s(label));
        options.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { }
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (values[position].equals(key.equals("language") ? AppPreferences.language(MainActivity.this) : prefs.text(key, "system"))) return;
                if (key.equals("language") && Build.VERSION.SDK_INT >= 33) {
                    getSystemService(LocaleManager.class).setApplicationLocales(values[position].equals("system") ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(values[position]));
                    return;
                }
                prefs.putText(key, values[position]);
                if (active()) startService(new Intent(MainActivity.this, PausaVpnService.class).setAction(PausaVpnService.REFRESH_UI));
                recreate();
            }
        });
        add(options, 0, 8);
    }
    private void shareApk() {
        android.net.Uri uri = ApkShareProvider.uri(this);
        Intent send = new Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("Pause APK", uri));
        try { startActivity(Intent.createChooser(send, s(R.string.share_chooser))); }
        catch (ActivityNotFoundException e) { toast(R.string.share_failed); }
    }
    private void notificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !prefs.text("notification_requested", "").equals("yes")) {
            prefs.putText("notification_requested", "yes"); requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
        } else {
            try { startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())); }
            catch (ActivityNotFoundException e) { toast(R.string.activation_failed); }
        }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) { super.onRequestPermissionsResult(request, permissions, results); if (request == 20 && page == Page.SETTINGS) build(); }
    private void help() {
        title(R.string.help); section(R.string.help_ads_title, R.string.help_ads_body); section(R.string.help_app_title, R.string.help_app_body);
        actionRow(R.string.background_title, s(R.string.background_summary), () -> open(Page.BACKGROUND));
        add(button(R.string.manage_exclusions, false, v -> open(Page.APPS)), 4, 12);
        if (active()) add(button(R.string.pause, false, v -> { pauseProtection(); home(); }), 0, 12);
        section(R.string.help_prizes_title, R.string.help_prizes_body); section(R.string.help_limits_title, R.string.help_limits_body);
    }
    private void privacy() {
        title(R.string.privacy); paragraph(R.string.privacy_intro); section(R.string.privacy_data_title, R.string.privacy_data_body);
        section(R.string.privacy_dns_title, R.string.privacy_dns_body); section(R.string.privacy_storage_title, R.string.privacy_storage_body); section(R.string.privacy_update_title, R.string.privacy_update_body);
    }
    private void advanced() {
        title(R.string.advanced); paragraph(R.string.advanced_intro);
        actionRow(R.string.domain_rules, s(R.string.domain_rules_summary), () -> open(Page.RULES)); actionRow(R.string.recent_activity, s(R.string.recent_activity_summary), () -> open(Page.RECENT));
        add(text(s(R.string.diagnostics), 20, INK, true), 28, 12); diagnostics = text("", 15, INK, false); diagnostics.setTextIsSelectable(true); add(diagnostics, 0, 10);
        paragraph(R.string.diagnostics_note); section(R.string.technical_notes, R.string.technical_notes_body); refreshDiagnostics();
        if (domains == 0 && !loadingDomains) {
            loadingDomains = true; background.execute(() -> {
                try { domains = new BlocklistStore(this).load().size(); } catch (Exception ignored) { }
                runOnUiThread(() -> { loadingDomains = false; if (!isDestroyed() && page == Page.ADVANCED) refreshDiagnostics(); });
            });
        }
    }
    private void refreshDiagnostics() {
        if (diagnostics == null) return; long total = Math.max(PausaVpnService.queries.get(), prefs.number("queries"));
        int size = PausaVpnService.ruleCount > 0 ? PausaVpnService.ruleCount : domains;
        String value = s(R.string.diagnostics_counts, number(total), size == 0 ? "…" : number(size), number(PausaVpnService.failures.get()));
        if (!PausaVpnService.error.isEmpty()) value += "\n\n" + PausaVpnService.error; setIfChanged(diagnostics, value);
    }
    private void rules() { title(R.string.rules_title); paragraph(R.string.rules_body); ruleGroup(R.string.allowed_domains, "allow"); ruleGroup(R.string.denied_domains, "deny"); }
    private void ruleGroup(int name, String key) {
        add(text(s(name), 20, INK, true), 24, 8); List<String> entries = new ArrayList<>(prefs.set(key)); Collections.sort(entries);
        if (entries.isEmpty()) paragraph(R.string.no_rules);
        for (String domain : entries) {
            LinearLayout line = row(); TextView label = text(domain, 15, INK, false); label.setTextIsSelectable(true); line.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            Button remove = button(R.string.remove, false, v -> { prefs.rule(key, domain, false); reload(); build(); }); remove.setContentDescription(s(R.string.remove_domain, domain)); line.addView(remove); add(line, 0, 4);
        }
        EditText field = new EditText(this); field.setTextSize(16); field.setSingleLine(true); field.setHint(R.string.domain_hint);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI); add(field, 8, 4);
        add(button(R.string.add, false, v -> { try { prefs.rule(key, field.getText().toString(), true); reload(); build(); } catch (IllegalArgumentException invalid) { field.setError(s(R.string.invalid_domain)); } }), 0, 12);
    }
    private void reload() { if (PausaVpnService.status.equals("on")) startService(new Intent(this, PausaVpnService.class).setAction(PausaVpnService.RELOAD)); }
    private void apps(int generation) {
        title(R.string.apps_title); paragraph(R.string.apps_body); boolean editingDisabled = active();
        if (editingDisabled) {
            paragraph(R.string.apps_pause_needed);
            add(button(R.string.pause_and_edit, true, v -> { pauseProtection(); handler.postDelayed(() -> { if (!isDestroyed() && page == Page.APPS) build(); }, 250); }), 0, 16);
        }
        EditText search = new EditText(this); search.setSingleLine(true); search.setHint(R.string.search_apps); search.setTextSize(16); search.setText(searchQuery); add(search, 12, 12);
        LinearLayout list = column(); content.addView(list); Runnable populate = () -> populateApps(list, search.getText().toString(), editingDisabled);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { searchQuery = s.toString(); if (cachedApps != null) populate.run(); }
            public void afterTextChanged(android.text.Editable value) { }
        });
        if (cachedApps != null) populate.run();
        else {
            list.addView(text(s(R.string.loading_apps), 15, MUTED, false));
            background.execute(() -> {
                List<AppEntry> loaded = new ArrayList<>();
                try {
                    PackageManager pm = getPackageManager(); Set<String> seen = new HashSet<>();
                    for (ResolveInfo item : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                        String pkg = item.activityInfo.packageName; if (!pkg.equals(getPackageName()) && seen.add(pkg)) loaded.add(new AppEntry(pkg, item.loadLabel(pm).toString()));
                    }
                    loaded.sort(Comparator.comparing(item -> item.label, String.CASE_INSENSITIVE_ORDER));
                    runOnUiThread(() -> { cachedApps = loaded; if (!isDestroyed() && page == Page.APPS && generation == renderGeneration) populate.run(); });
                } catch (RuntimeException e) { runOnUiThread(() -> { if (!isDestroyed() && generation == renderGeneration) { list.removeAllViews(); list.addView(text(s(R.string.apps_failed), 15, ERROR, false)); } }); }
            });
        }
    }
    private void populateApps(LinearLayout list, String query, boolean disabled) {
        list.removeAllViews(); String filter = query.trim().toLowerCase(Locale.ROOT); Set<String> excluded = prefs.set("excluded");
        for (AppEntry entry : cachedApps) {
            if (!entry.label.toLowerCase(Locale.ROOT).contains(filter)) continue;
            CheckBox box = new CheckBox(this); box.setText(entry.label); box.setTextSize(16); box.setTextColor(INK); box.setContentDescription(s(R.string.exclude_app, entry.label));
            box.setMinHeight(dp(60)); box.setPadding(0, dp(10), 0, dp(10)); box.setChecked(excluded.contains(entry.pkg)); box.setEnabled(!disabled);
            box.setOnCheckedChangeListener((view, checked) -> { Set<String> values = prefs.set("excluded"); if (checked) values.add(entry.pkg); else values.remove(entry.pkg); prefs.set("excluded", values); }); list.addView(box);
        }
        if (list.getChildCount() == 0) list.addView(text(s(R.string.no_apps), 15, MUTED, false));
    }
    private void recent() {
        title(R.string.recent_activity); List<String> entries = PausaVpnService.recent();
        if (entries.isEmpty()) { section(R.string.recent_empty_title, R.string.recent_empty_body); return; }
        paragraph(R.string.recent_body);
        for (String domain : entries) actionRow(domain, null, () -> new AlertDialog.Builder(this).setTitle(domain).setMessage(R.string.allow_domain_question)
            .setPositiveButton(R.string.allow, (dialog, which) -> { prefs.rule("allow", domain, true); reload(); toast(R.string.allowed_saved); }).setNegativeButton(R.string.cancel, null).show());
    }
    private void about() {
        title(R.string.about); paragraph(s(R.string.version, BuildConfig.VERSION_NAME)); paragraph(R.string.about_body); section(R.string.whats_new, R.string.whats_new_body);
        actionRow(R.string.licenses, s(R.string.licenses_summary), () -> open(Page.LICENSES));
    }
    private void licenses() {
        title(R.string.licenses);
        for (String source : new String[]{"hosts-license.txt", "hagezi-license.txt"}) try (java.io.InputStream in = getAssets().open(source)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] data = new byte[4096]; int count;
            while ((count = in.read(data)) > 0) bytes.write(data, 0, count);
            TextView license = text(bytes.toString("UTF-8"), 14, INK, false); license.setTextIsSelectable(true); add(license, 0, 20); paragraph(R.string.licenses_note);
        } catch (java.io.IOException e) { paragraph(R.string.licenses_failed); }
    }
    private void title(int id) { add(text(s(id), 28, INK, true), 0, 16); }
    private void paragraph(int id) { paragraph(s(id)); }
    private void paragraph(String copy) { add(text(copy, 16, MUTED, false), 0, 12); }
    private void section(int title, int body) { add(text(s(title), 20, INK, true), 20, 8); paragraph(body); }
    private TextView text(String copy, int sp, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(copy); view.setTextSize(sp); view.setTextColor(color); view.setLineSpacing(dp(3), 1);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return view;
    }
    private Button button(int label, boolean filled, View.OnClickListener listener) {
        Button button = new Button(this, null, filled ? android.R.attr.buttonStyle : android.R.attr.borderlessButtonStyle);
        button.setText(label); button.setAllCaps(false); button.setTextSize(15); button.setMinHeight(dp(48)); button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setStateListAnimator(null); button.setElevation(0);
        if (filled) {
            int[][] states = {new int[]{-android.R.attr.state_enabled}, new int[0]}; button.setBackgroundTintList(new ColorStateList(states, new int[]{LINE, GREEN}));
            button.setTextColor(new ColorStateList(states, new int[]{MUTED, ON_PRIMARY}));
        } else button.setTextColor(GREEN);
        buttonIcon(button, label, filled); button.setOnClickListener(listener); return button;
    }
    private void buttonIcon(Button button, int label, boolean filled) {
        int resource = label == R.string.activate || label == R.string.setup_start ? R.drawable.ic_play
            : label == R.string.pause || label == R.string.pause_and_edit ? R.drawable.ic_pause
            : label == R.string.back ? R.drawable.ic_arrow_left : label == R.string.settings ? R.drawable.ic_settings : 0;
        if (resource == 0) { button.setCompoundDrawablesRelative(null, null, null, null); return; }
        Drawable icon = getDrawable(resource).mutate(); icon.setTint(filled ? ON_PRIMARY : GREEN); icon.setBounds(0, 0, dp(20), dp(20));
        button.setCompoundDrawablePadding(dp(8)); button.setCompoundDrawablesRelative(icon, null, null, null);
    }
    private int rowIcon(int label) {
        if (label == R.string.background_title) return R.drawable.ic_shield;
        if (label == R.string.personalize) return R.drawable.ic_settings;
        if (label == R.string.excluded_apps) return R.drawable.ic_apps;
        if (label == R.string.update_protection) return R.drawable.ic_refresh;
        if (label == R.string.notifications) return R.drawable.ic_bell;
        if (label == R.string.help || label == R.string.help_short) return R.drawable.ic_help;
        if (label == R.string.privacy) return R.drawable.ic_shield;
        if (label == R.string.advanced) return R.drawable.ic_code;
        if (label == R.string.about) return R.drawable.ic_info;
        if (label == R.string.share_app) return R.drawable.ic_share;
        if (label == R.string.licenses) return R.drawable.ic_file;
        if (label == R.string.domain_rules) return R.drawable.ic_filter;
        if (label == R.string.recent_activity) return R.drawable.ic_activity;
        return 0;
    }
    private View actionRow(int title, String body, Runnable action) { return actionRow(s(title), body, action, rowIcon(title)); }
    private View actionRow(String title, String body, Runnable action) { return actionRow(title, body, action, 0); }
    private View actionRow(String title, String body, Runnable action, int resource) {
        LinearLayout line = row(); line.setPadding(0, dp(16), 0, dp(16)); line.setMinimumHeight(dp(56));
        if (resource != 0) {
            ImageView icon = new ImageView(this); icon.setImageResource(resource); icon.setImageTintList(ColorStateList.valueOf(GREEN)); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(24), dp(24)); params.rightMargin = dp(16); line.addView(icon, params);
        }
        LinearLayout copy = column(); copy.addView(text(title, 16, INK, true)); line.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        if (body != null) { TextView summary = text(body, 14, MUTED, false); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(4); copy.addView(summary, params); }
        TypedValue selectable = new TypedValue(); getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectable, true); line.setBackgroundResource(selectable.resourceId);
        line.setClickable(true); line.setFocusable(true); line.setContentDescription(body == null ? title : title + ". " + body);
        line.setAccessibilityDelegate(new View.AccessibilityDelegate() { @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName()); } });
        line.setOnClickListener(v -> action.run()); content.addView(line); divider(); return line;
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private void add(View view, int top, int bottom) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(top), 0, dp(bottom)); content.addView(view, params); }
    private void divider() { View line = new View(this); line.setBackgroundColor(LINE); content.addView(line, new LinearLayout.LayoutParams(-1, dp(1))); }
    private void toast(int id) { Toast.makeText(this, id, Toast.LENGTH_LONG).show(); }
    private static void setIfChanged(TextView view, String value) { if (!view.getText().toString().equals(value)) view.setText(value); }
    private static final class AppEntry { final String pkg, label; AppEntry(String pkg, String label) { this.pkg = pkg; this.label = label; } }
}
