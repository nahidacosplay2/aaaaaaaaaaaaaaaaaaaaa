package app.zapret.aris;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.zapretcap.tgproxy.Stats;
import org.zapretcap.tgproxy.TgWsProxy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Главный экран: включить, выключить и выбрать конфиг отдельно для Wi-Fi и мобильной сети. */
public class MainActivity extends Activity implements AppState.Listener {
    private static final AtomicBoolean QUICK_TEST_RUNNING = new AtomicBoolean();
    private static final int REQUEST_VPN = 1;
    private static final int REQUEST_SOCKS_SETTINGS = 5;

    private final List<String> names = new ArrayList<>();
    private final List<String> visibleNames = new ArrayList<>();
    private boolean favoritesOnly;
    private boolean selectSocksAfterSettings;
    private boolean startAfterSocksSettings;

    private SharedPreferences prefs;
    private TextView status;
    private TextView error;
    private TextView hint;
    private View wifiNameAccess;
    private static final int REQUEST_LOCATION = 3;
    private static final int REQUEST_BACKGROUND_LOCATION = 4;
    private Button toggle;
    private View bgCard;
    private TextView bgText;
    private List<String> editingScopes = java.util.Collections.singletonList(NetKind.WIFI);
    private android.widget.LinearLayout bgButtons;
    private ListView list;
    private ConfigAdapter adapter;
    private View hero;
    private ImageView heroIcon;
    private TextView statusDetail;
    private TextView tabWifi;
    private TextView tabCell;
    private TextView filterAll;
    private TextView filterFavorites;
    private TextView emptyFavorites;
    private TextView modeZapret;
    private TextView modeSocks;
    private TextView modeDescription;
    private TextView socksSummary;
    private View zapretActions;
    private View configSelector;
    private View socksRoute;
    private View bypassSettingsButton;
    /** Для какой сети сейчас открыт выбор конфига. */
    private String editingKind;
    private String lastKind;
    private TextView tgStatus;
    private TextView tgStrategy;
    private Switch tgSwitch;
    private View testButton;
    private View quickTestButton;
    private View diagnosticsButton;
    private AlertDialog testDialog;
    private AlertDialog quickDialog;
    private QuickTester quickTester;
    private boolean activityStarted;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable testTick = this::updateTest;
    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            updateTelegram();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this);
        setContentView(R.layout.activity_main);
        SystemBars.apply(this);
        prefs = BypassVpnService.prefs(this);

        list = findViewById(R.id.list);
        LayoutInflater inflater = getLayoutInflater();
        View header = inflater.inflate(R.layout.main_header, list, false);
        View footer = inflater.inflate(R.layout.main_footer, list, false);
        list.addHeaderView(header, null, false);
        list.addFooterView(footer, null, false);

        status = header.findViewById(R.id.status);
        statusDetail = header.findViewById(R.id.status_detail);
        error = header.findViewById(R.id.error);
        hint = header.findViewById(R.id.hint);
        wifiNameAccess = header.findViewById(R.id.wifi_name_access);
        wifiNameAccess.setOnClickListener(v -> requestWifiName());
        hero = header.findViewById(R.id.hero);
        heroIcon = header.findViewById(R.id.hero_icon);
        toggle = header.findViewById(R.id.toggle);
        bgCard = header.findViewById(R.id.bg_card);
        bgText = header.findViewById(R.id.bg_text);
        bgButtons = header.findViewById(R.id.bg_buttons);
        bypassSettingsButton = footer.findViewById(R.id.open_bypass_settings);
        bypassSettingsButton.setOnClickListener(
                v -> startActivity(new Intent(this, BypassSettingsActivity.class)));
        footer.findViewById(R.id.open_sources).setOnClickListener(
                v -> openUrl("https://github.com/nahidacosplay2/aaaaaaaaaaaaaaaaaaaaa"));
        modeZapret = header.findViewById(R.id.mode_zapret);
        modeSocks = header.findViewById(R.id.mode_socks);
        modeDescription = header.findViewById(R.id.mode_description);
        socksSummary = header.findViewById(R.id.socks_summary);
        zapretActions = header.findViewById(R.id.zapret_actions);
        configSelector = header.findViewById(R.id.config_selector);
        socksRoute = header.findViewById(R.id.socks_route);
        modeZapret.setOnClickListener(v -> selectMode(ConnectionMode.ZAPRET));
        modeSocks.setOnClickListener(v -> selectMode(ConnectionMode.SOCKS5));
        header.findViewById(R.id.open_socks_settings).setOnClickListener(v -> openSocksSettings(false, false));
        tabWifi = header.findViewById(R.id.tab_wifi);
        tabCell = header.findViewById(R.id.tab_cell);
        tabWifi.setOnClickListener(v -> showKind(NetKind.WIFI));
        tabCell.setOnClickListener(v -> showKind(NetKind.CELL));
        filterAll = header.findViewById(R.id.filter_all);
        filterFavorites = header.findViewById(R.id.filter_favorites);
        emptyFavorites = header.findViewById(R.id.config_empty_favorites);
        filterAll.setOnClickListener(v -> setFavoritesFilter(false));
        filterFavorites.setOnClickListener(v -> setFavoritesFilter(true));

        adapter = new ConfigAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount();
            if (index >= 0 && index < visibleNames.size()) {
                onStrategyPicked(visibleNames.get(index));
            }
        });

        toggle.setOnClickListener(v -> onToggle());
        testButton = header.findViewById(R.id.test_configs);
        testButton.setOnClickListener(v -> {
            if (QUICK_TEST_RUNNING.get()) {
                Toast.makeText(this, R.string.quick_test_running, Toast.LENGTH_SHORT).show();
                return;
            }
            ConfigTester.start(this);
            updateTest();
        });
        quickTestButton = header.findViewById(R.id.quick_test);
        quickTestButton.setOnClickListener(v -> runQuickTest());
        diagnosticsButton = header.findViewById(R.id.diagnostics);
        diagnosticsButton.setOnClickListener(v -> showDiagnostics());
        header.findViewById(R.id.open_domains).setOnClickListener(
                v -> startActivity(new Intent(this, DomainListActivity.class)));

        editingKind = NetKind.current(this);

        tgStatus = header.findViewById(R.id.tg_status);
        tgSwitch = header.findViewById(R.id.tg_switch);
        tgSwitch.setOnClickListener(v -> {
            if (tgSwitch.isChecked()) {
                TelegramProxyService.start(this);
                askBatteryExemption();
            } else {
                TelegramProxyService.stop(this);
            }
        });
        header.findViewById(R.id.tg_connect).setOnClickListener(v -> connectTelegram());
        tgStrategy = header.findViewById(R.id.tg_strategy_pick);
        tgStrategy.setOnClickListener(v -> pickTgStrategy());
        header.findViewById(R.id.tg_open_settings).setOnClickListener(
                v -> startActivity(new Intent(this, TgProxySettingsActivity.class)));

        loadStrategies();
        updateModeUi();
        requestNotifications();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        AppState.setListener(this);
        // The app mode and endpoint can change while the settings screen is open.
        updateModeUi();
        // за время, пока экрана не было, телефон мог сменить сеть
        String kind = NetKind.current(this);
        if (!kind.equals(lastKind)) {
            lastKind = kind;
            showKind(kind);
        } else if (!NetKind.scopes(this, editingKind).equals(editingScopes)) {
            // другая Wi-Fi сеть или стало известно её имя: у неё свой выбор и свои итоги подбора
            showKind(editingKind);
        } else {
            // доступ к имени сети мог появиться в настройках, пока приложения не было на экране
            updateWifiHint();
        }
        // работающий движок читает списки и настройки только при запуске
        boolean listsChanged = UserLists.consumeChanged();
        boolean settingsChanged = BypassSettings.consumeChanged();
        boolean modeChanged = ConnectionMode.consumeChanged();
        boolean socksChanged = Socks5Settings.consumeChanged();
        boolean socksMode = ConnectionMode.isSocks5(this);
        boolean routeSettingsChanged = modeChanged || (socksMode ? socksChanged : (listsChanged || settingsChanged));
        if (routeSettingsChanged && AppState.phase != AppState.Phase.OFF) {
            start();
            if (!socksMode && (listsChanged || settingsChanged)) {
                Toast.makeText(this, R.string.domains_applied, Toast.LENGTH_SHORT).show();
            }
        }
        updateModeUi();
        // человек мог вернуться из настроек: проверяем заново и убираем уведомление, если всё исправлено
        updateBackground();
        onStateChanged();
        statsTick.run();
        updateTest();
        if (quickTester != null && QUICK_TEST_RUNNING.get() && quickDialog == null) {
            quickDialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.quick_test_title)
                    .setMessage(R.string.quick_test_cancelling)
                    .setCancelable(false)
                    .show();
        }
        // служба могла быть остановлена системой, пока приложения не было на экране
        if (TgProxySettings.prefs(this).getBoolean(TgProxySettings.ENABLED, false)) {
            if (AppState.tgPhase == AppState.Phase.OFF) {
                TelegramProxyService.start(this);
            }
            askBatteryExemption();
        }
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        handler.removeCallbacks(statsTick);
        handler.removeCallbacks(testTick);
        closeTestDialog();
        if (quickTester != null) {
            quickTester.cancel();
        }
        dismissQuickDialog();
        AppState.setListener(null);
        super.onStop();
    }

    private String selected() {
        return Data.strategyFor(this, editingKind);
    }

    private void updateModeUi() {
        boolean socks = ConnectionMode.isSocks5(this);
        modeZapret.setSelected(!socks);
        modeSocks.setSelected(socks);
        modeDescription.setText(socks ? R.string.mode_description_socks5 : R.string.mode_description_zapret);
        zapretActions.setVisibility(socks ? View.GONE : View.VISIBLE);
        configSelector.setVisibility(socks ? View.GONE : View.VISIBLE);
        socksRoute.setVisibility(socks ? View.VISIBLE : View.GONE);
        bypassSettingsButton.setVisibility(socks ? View.GONE : View.VISIBLE);
        socksSummary.setText(Socks5Settings.summary(this));
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void selectMode(String mode) {
        if (ConnectionMode.SOCKS5.equals(mode) && !Socks5Settings.isConfigured(this)) {
            openSocksSettings(true, false);
            return;
        }
        if (mode.equals(ConnectionMode.get(this))) {
            return;
        }
        ConnectionMode.set(this, mode);
        ConnectionMode.consumeChanged();
        updateModeUi();
        if (AppState.phase != AppState.Phase.OFF) {
            start();
        }
    }

    private void openSocksSettings(boolean selectSocksOnSave, boolean startWhenSaved) {
        selectSocksAfterSettings = selectSocksOnSave;
        startAfterSocksSettings = startWhenSaved;
        startActivityForResult(new Intent(this, Socks5SettingsActivity.class), REQUEST_SOCKS_SETTINGS);
    }

    /** Показать выбор конфига для сети. Сеть, в которой телефон сейчас, отмечена в названии вкладки. */
    private void showKind(String kind) {
        editingKind = kind;
        // какая Wi-Fi сеть сейчас: итоги подбора и выбор хранятся для каждой отдельно
        editingScopes = NetKind.scopes(this, kind);
        sortConfigs();
        String current = NetKind.current(this);
        tabWifi.setSelected(NetKind.WIFI.equals(kind));
        tabCell.setSelected(NetKind.CELL.equals(kind));
        tabWifi.setText(NetKind.WIFI.equals(current)
                ? getString(R.string.net_current_suffix, getString(R.string.net_wifi)) : getString(R.string.net_wifi));
        tabCell.setText(NetKind.CELL.equals(current)
                ? getString(R.string.net_current_suffix, getString(R.string.net_cell)) : getString(R.string.net_cell));
        adapter.notifyDataSetChanged();
    }

    /** Список длинный: то, что нужно чаще всего, всегда наверху. Порядок не меняется, пока человек выбирает конфиг. */
    private void sortConfigs() {
        String chosen = selected();
        String recommended = Data.recommended(editingKind);
        java.util.Set<String> favorites = Data.favorites(this);
        java.util.Collections.sort(names, (a, b) -> {
            int rankA = a.equals(chosen) ? 0 : favorites.contains(a) ? 1 : a.equals(recommended) ? 2 : 3;
            int rankB = b.equals(chosen) ? 0 : favorites.contains(b) ? 1 : b.equals(recommended) ? 2 : 3;
            return rankA != rankB ? rankA - rankB : Data.compareNatural(a, b);
        });
        refreshVisibleNames();
    }

    private void refreshVisibleNames() {
        visibleNames.clear();
        java.util.Set<String> favorites = Data.favorites(this);
        int favoriteCount = 0;
        for (String name : names) {
            boolean favorite = favorites.contains(name);
            if (favorite) {
                favoriteCount++;
            }
            if (!favoritesOnly || favorite) {
                visibleNames.add(name);
            }
        }
        if (filterAll != null) {
            updateFilterUi(favoriteCount);
        }
    }

    private void setFavoritesFilter(boolean favorites) {
        favoritesOnly = favorites;
        refreshVisibleNames();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void updateFilterUi(int favoriteCount) {
        filterAll.setSelected(!favoritesOnly);
        filterFavorites.setSelected(favoritesOnly);
        filterFavorites.setText(getString(R.string.config_filter_favorites_count, favoriteCount));
        emptyFavorites.setVisibility(favoritesOnly && visibleNames.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void toggleFavorite(String name) {
        boolean added = Data.toggleFavorite(this, name);
        Toast.makeText(this, added ? R.string.config_favorite_added : R.string.config_favorite_removed,
                Toast.LENGTH_SHORT).show();
        sortConfigs();
        adapter.notifyDataSetChanged();
    }

    private void loadStrategies() {
        names.clear();
        try {
            names.addAll(Data.strategies(this));
        } catch (IOException e) {
            AppState.set(AppState.Phase.OFF, null, e.getMessage());
        }
        if (!names.contains(selected()) && !names.isEmpty()) {
            // такого конфига больше нет, например после обновления приложения
            Data.setStrategyFor(this, editingKind, names.get(0));
        }
        sortConfigs();
        adapter.notifyDataSetChanged();
        updateWifiHint();
    }

    @Override
    public void onStateChanged() {
        AppState.Phase phase = AppState.phase;
        boolean on = phase == AppState.Phase.ON;
        if (on) {
            status.setText(R.string.status_on_short);
            String detail = getString(R.string.status_detail_on, AppState.strategy, getString(
                    NetKind.CELL.equals(NetKind.current(this)) ? R.string.net_cell : R.string.net_wifi));
            statusDetail.setText(AppState.routeNotice == null ? detail : detail + "\n" + AppState.routeNotice);
            toggle.setText(R.string.turn_off);
        } else if (phase == AppState.Phase.STARTING) {
            status.setText(R.string.status_starting);
            String detail = AppState.strategy;
            statusDetail.setText(AppState.routeNotice == null ? detail : detail + "\n" + AppState.routeNotice);
            toggle.setText(R.string.turn_off);
        } else {
            status.setText(R.string.status_off_short);
            statusDetail.setText(R.string.status_detail_off);
            toggle.setText(R.string.turn_on);
        }
        hero.setBackgroundResource(on ? R.drawable.bg_card_on : R.drawable.bg_card);
        int accent = getColor(R.color.accent);
        int muted = getColor(R.color.muted);
        heroIcon.setImageTintList(android.content.res.ColorStateList.valueOf(on ? getColor(R.color.on_accent) : muted));
        heroIcon.setBackgroundTintList(android.content.res.ColorStateList.valueOf(on ? accent : getColor(R.color.surface_alt)));
        // основное действие выделено, когда обход выключен: выключение не должно быть самым заметным на экране
        toggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(on ? getColor(R.color.surface) : accent));
        toggle.setTextColor(on ? getColor(R.color.on_surface) : getColor(R.color.on_accent));
        String message = AppState.error;
        error.setText(message);
        error.setVisibility(message == null || message.isEmpty() ? View.GONE : View.VISIBLE);
        updateTelegram();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText(url, url));
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    /** Конфиг прокси выбирается отдельно от основного обхода: с некоторыми конфигами YouTube работает, а Telegram нет. */
    private void pickTgStrategy() {
        java.util.List<String> names = TgProxySettings.strategyChoices(this);
        String[] labels = new String[names.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = TgProxySettings.strategyLabel(this, names.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.tg_section_strategy)
                .setSingleChoiceItems(labels, Math.max(names.indexOf(TgProxySettings.strategy(this)), 0), (dialog, which) -> {
                    TgProxySettings.setStrategy(this, names.get(which));
                    dialog.dismiss();
                    updateTelegram();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateTelegram() {
        tgStrategy.setText(getString(R.string.tg_strategy_pick,
                TgProxySettings.strategyLabel(this, TgProxySettings.strategy(this))));
        AppState.Phase phase = AppState.tgPhase;
        TgWsProxy proxy = AppState.tgProxy;
        tgSwitch.setChecked(phase != AppState.Phase.OFF);
        if (phase == AppState.Phase.ON && proxy != null) {
            Stats stats = proxy.stats();
            String text = proxy.stats().recentBadKey() ? getString(R.string.tg_status_bad_key)
                    : getString(R.string.tg_status_on, (int) stats.active(),
                            Stats.humanBytes(stats.bytesDown()), Stats.humanBytes(stats.bytesUp()));
            String engineNotice = TgEngine.notice();
            tgStatus.setText(engineNotice == null ? text : text + "\n" + engineNotice);
        } else if (phase == AppState.Phase.STARTING) {
            tgStatus.setText(R.string.tg_status_starting);
        } else {
            String message = AppState.tgError;
            tgStatus.setText(message != null ? message : getString(R.string.tg_status_off));
        }
    }

    /**
     * Экономия батареи на многих телефонах усыпляет приложение даже со службой, и прокси перестаёт отвечать,
     * а Telegram переподключается по кругу. Спрашиваем один раз: решение за человеком, и система покажет свой запрос.
     */
    private void askBatteryExemption() {
        if (prefs.getBoolean("battery_asked", false)) {
            return;
        }
        prefs.edit().putBoolean("battery_asked", true).apply();
        if (getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            // у производителя нет такого экрана: остаётся настройка вручную, она описана в README
        }
    }

    /** Telegram сам спросит, добавить ли прокси: нужна только ссылка, а прокси к этому моменту должен работать. */
    private void connectTelegram() {
        if (AppState.tgPhase == AppState.Phase.OFF) {
            TelegramProxyService.start(this);
        }
        String link = TgProxySettings.load(this).link();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
        } catch (ActivityNotFoundException e) {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("tg proxy", link));
            Toast.makeText(this, R.string.tg_no_telegram, Toast.LENGTH_LONG).show();
        }
    }

    private void updateTest() {
        ConfigTester test = ConfigTester.pending();
        if (test == null) {
            closeTestDialog();
            return;
        }
        if (test.finished()) {
            closeTestDialog();
            test.markShown();
            showTestResults(test);
            return;
        }
        if (testDialog == null) {
            testDialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.test_title)
                    .setMessage(test.progress())
                    .setCancelable(false)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> test.cancel())
                    .show();
            // проверка идёт несколько минут, а уснувший телефон исказил бы результат
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            testDialog.setMessage(test.progress());
        }
        handler.removeCallbacks(testTick);
        handler.postDelayed(testTick, 500);
    }

    private void closeTestDialog() {
        if (testDialog != null) {
            testDialog.dismiss();
            testDialog = null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showTestResults(ConfigTester test) {
        if (test.cancelled()) {
            return;
        }
        if (test.error() != null || test.results().isEmpty()) {
            AppState.set(AppState.phase, AppState.strategy, getString(R.string.test_failed, String.valueOf(test.error())));
            return;
        }
        List<ConfigTester.Result> results = test.results();
        ConfigTester.Result best = results.get(0);
        int total = test.total();
        ConfigTester.Result baseline = test.baseline();
        int direct = baseline.ok;
        int freezeTotal = test.freezeTotal();
        // как в Linux-версии: сначала сайты, при равенстве - меньше фризов
        boolean helps = best.ok > direct || (best.ok == direct && best.frozenPermille() < baseline.frozenPermille());
        // конфиг запоминается для той сети, в которой шла проверка
        showKind(test.kind());
        if (helps) {
            selectStrategy(best.name);
        }
        List<String> rows = new ArrayList<>();
        for (ConfigTester.Result result : results) {
            int frozen = result.freeze == null ? 0 : result.freeze.frozen;
            int checked = result.freeze == null ? 0 : result.freeze.total();
            rows.add(result.ttl > 0
                    ? getString(R.string.test_row_ttl, result.ok, total, result.name, frozen, checked, result.ttl)
                    : getString(R.string.test_row, result.ok, total, result.name, frozen, checked));
        }
        TextView header = new TextView(this);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        header.setPadding(padding, padding / 2, padding, padding / 2);
        int bestFrozen = best.freeze == null ? 0 : best.freeze.frozen;
        int directFrozen = baseline.freeze == null ? 0 : baseline.freeze.frozen;
        header.setText(helps
                ? getString(R.string.test_chosen, best.name, best.ok, total, bestFrozen, freezeTotal, direct,
                        directFrozen, test.freezeTargets())
                : getString(R.string.test_none, direct, total, directFrozen, freezeTotal));
        ListView rowsView = new ListView(this);
        rowsView.addHeaderView(header, null, false);
        rowsView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.test_title)
                .setView(rowsView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        rowsView.setOnItemClickListener((parent, view, position, id) -> {
            // первая строка списка - заголовок
            selectStrategy(results.get(position - rowsView.getHeaderViewsCount()).name);
            dialog.dismiss();
        });
    }

    private void runQuickTest() {
        if (ConnectionMode.isSocks5(this)) {
            return;
        }
        if (ConfigTester.pending() != null) {
            Toast.makeText(this, R.string.full_test_running, Toast.LENGTH_SHORT).show();
            updateTest();
            return;
        }
        String kind = NetKind.current(this);
        String strategy = Data.strategyFor(this, kind);
        int activePort = BypassVpnService.enginePort;
        QuickTester tester = new QuickTester(this);
        if (!QUICK_TEST_RUNNING.compareAndSet(false, true)) {
            Toast.makeText(this, R.string.quick_test_running, Toast.LENGTH_SHORT).show();
            return;
        }
        quickTester = tester;
        quickTestButton.setEnabled(false);
        testButton.setEnabled(false);
        quickDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.quick_test_title)
                .setMessage(R.string.quick_test_starting)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        quickDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            tester.cancel();
            quickDialog.setMessage(R.string.quick_test_cancelling);
            quickDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        });
        Thread thread = new Thread(() -> {
            try {
                QuickTester.Result result = tester.run(kind, strategy, activePort,
                        message -> handler.post(() -> {
                            if (quickTester == tester && quickDialog != null && quickDialog.isShowing()
                                    && !tester.isCancelled()) {
                                quickDialog.setMessage(message);
                            }
                        }));
                handler.post(() -> finishQuickTest(tester, result, null, false));
            } catch (InterruptedException cancelled) {
                handler.post(() -> finishQuickTest(tester, null, null, true));
            } catch (IOException | RuntimeException e) {
                String message = String.valueOf(e.getMessage());
                handler.post(() -> finishQuickTest(tester, null, message, false));
            } finally {
                QUICK_TEST_RUNNING.set(false);
            }
        }, "quick-config-test");
        thread.setDaemon(true);
        thread.start();
    }

    private void dismissQuickDialog() {
        if (quickDialog != null) {
            quickDialog.dismiss();
            quickDialog = null;
        }
    }

    private void closeQuickDialog() {
        dismissQuickDialog();
        quickTester = null;
        if (quickTestButton != null) {
            quickTestButton.setEnabled(true);
        }
        if (testButton != null) {
            testButton.setEnabled(true);
        }
    }

    private void finishQuickTest(QuickTester tester, QuickTester.Result result, String failure, boolean cancelled) {
        if (quickTester != tester) {
            return;
        }
        boolean showResult = activityStarted && !isFinishing() && !isDestroyed();
        closeQuickDialog();
        if (!showResult || cancelled || tester.isCancelled()) {
            return;
        }
        if (failure != null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.quick_test_title)
                    .setMessage(getString(R.string.quick_test_failed, failure))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } else if (result != null) {
            showQuickResult(result);
        }
    }

    private void showQuickResult(QuickTester.Result result) {
        StringBuilder text = new StringBuilder(getString(R.string.quick_test_result_summary,
                result.strategy, result.strategyCount(), result.targets.size(),
                result.directCount(), result.targets.size()));
        for (int i = 0; i < result.targets.size(); i++) {
            text.append("\n\n").append(getString(R.string.quick_test_result_row,
                    result.targets.get(i),
                    getString(Boolean.TRUE.equals(result.viaStrategy.get(i))
                            ? R.string.quick_test_open : R.string.quick_test_failed_sample),
                    getString(Boolean.TRUE.equals(result.direct.get(i))
                            ? R.string.quick_test_open : R.string.quick_test_failed_sample)));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.quick_test_title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showDiagnostics() {
        String kind = NetKind.current(this);
        String strategy = Data.strategyFor(this, kind);
        String lastWorking = Data.lastWorking(this, kind);
        String mode = ConnectionMode.isSocks5(this) ? getString(R.string.mode_socks5) : getString(R.string.mode_zapret);
        StringBuilder report = new StringBuilder();
        report.append(getString(R.string.diagnostics_app)).append(Build.VERSION.SDK_INT)
                .append("\n").append(getString(R.string.diagnostics_network, getString(
                        NetKind.CELL.equals(kind) ? R.string.net_cell : R.string.net_wifi)))
                .append("\n").append(getString(R.string.diagnostics_mode, mode))
                .append("\n").append(getString(R.string.diagnostics_strategy, strategy))
                .append("\n").append(getString(R.string.diagnostics_last_working,
                        lastWorking == null ? getString(R.string.diagnostics_none) : lastWorking))
                .append("\n").append(getString(R.string.diagnostics_failover,
                        Data.autoFailoverEnabled(this) ? getString(R.string.diagnostics_enabled)
                                : getString(R.string.diagnostics_disabled),
                        Data.failureAction(this).equals(Data.FAILURE_ACTION_DIRECT)
                                ? getString(R.string.failure_action_direct)
                                : getString(R.string.failure_action_stop)))
                .append("\n").append(getString(R.string.diagnostics_route,
                        AppState.phase.name(), AppState.strategy == null ? "—" : AppState.strategy,
                        BypassVpnService.enginePort > 0 ? getString(R.string.diagnostics_engine_running)
                                : getString(R.string.diagnostics_engine_stopped)))
                .append("\n").append(getString(R.string.diagnostics_favorites, Data.favorites(this).size()));
        if (AppState.routeNotice != null && !AppState.routeNotice.isEmpty()) {
            report.append("\n").append(getString(R.string.diagnostics_route_notice, AppState.routeNotice));
        }
        if (AppState.error != null && !AppState.error.isEmpty()) {
            report.append("\n\n").append(getString(R.string.diagnostics_last_error, AppState.error));
        }
        String tgEngineNotice = TgEngine.notice();
        if (tgEngineNotice != null) {
            report.append("\n").append(getString(R.string.diagnostics_tg_engine, tgEngineNotice));
        }
        String crash = CrashLog.read(this);
        if (crash != null && !crash.trim().isEmpty()) {
            String[] lines = crash.trim().split("\\r?\\n");
            report.append("\n\n").append(getString(R.string.diagnostics_crash));
            for (int i = Math.max(0, lines.length - 8); i < lines.length; i++) {
                report.append("\n").append(lines[i]);
            }
        }
        List<String> logs = org.zapretcap.tgproxy.ProxyLog.recent();
        if (!logs.isEmpty()) {
            report.append("\n\n").append(getString(R.string.diagnostics_tg_log));
            for (int i = Math.max(0, logs.size() - 8); i < logs.size(); i++) {
                report.append("\n").append(logs.get(i));
            }
        }
        String diagnostic = report.toString();
        new AlertDialog.Builder(this)
                .setTitle(R.string.diagnostics_title)
                .setMessage(diagnostic)
                .setPositiveButton(R.string.diagnostics_copy, (dialog, which) -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostics_title), diagnostic));
                    Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private void selectStrategy(String name) {
        onStrategyPicked(name);
    }

    /** Какая Wi-Fi сеть сейчас и, если её имя скрыто, кнопка, чтобы дать доступ. */
    private void updateWifiHint() {
        NetKind.WifiId wifi = NetKind.wifiId(this);
        String text = getString(R.string.hint_vpn);
        if (wifi != null) {
            text += " " + (wifi.ssid != null ? getString(R.string.hint_wifi_named, wifi.ssid)
                    : getString(R.string.hint_wifi_network, wifi.gateway));
        }
        hint.setText(text);
        boolean backgroundMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED;
        wifiNameAccess.setVisibility(wifi != null && (wifi.ssid == null || backgroundMissing) ? View.VISIBLE : View.GONE);
    }

    /**
     * Доступ к имени Wi-Fi по шагам: сначала местоположение, затем «Разрешить всегда» (без него служба в фоне имени
     * не узнает), и включённая геолокация.
     */
    private void requestWifiName() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.wifi_background_title)
                    .setMessage(R.string.wifi_background_text)
                    .setPositiveButton(R.string.wifi_background_go, (dialog, which) -> requestPermissions(
                            new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQUEST_BACKGROUND_LOCATION))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        android.location.LocationManager location = getSystemService(android.location.LocationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && location != null && !location.isLocationEnabled()) {
            Toast.makeText(this, R.string.wifi_location_off, Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
                // нет такого экрана: подсказка уже показана
            }
        }
        updateWifiHint();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_LOCATION || requestCode == REQUEST_BACKGROUND_LOCATION) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && requestCode == REQUEST_LOCATION) {
                // следующий шаг: «Разрешить всегда» и геолокация
                requestWifiName();
            }
            // теперь сеть может узнаваться по имени: у неё своя запись выбора
            showKind(editingKind);
        }
    }

    /** Карточка с настройками, из-за которых система может останавливать обход. */
    private void updateBackground() {
        BackgroundChecks.refreshNotification(this);
        List<BackgroundChecks.Issue> issues = BackgroundChecks.issues(this);
        bgCard.setVisibility(issues.isEmpty() ? View.GONE : View.VISIBLE);
        bgButtons.removeAllViews();
        if (issues.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder(getString(R.string.bg_card_text));
        boolean unverifiable = false;
        int margin = (int) (8 * getResources().getDisplayMetrics().density);
        for (BackgroundChecks.Issue issue : issues) {
            text.append("\n\n• ").append(issue.title).append(". ").append(issue.text);
            TextView button = (TextView) getLayoutInflater().inflate(R.layout.item_tonal_button, bgButtons, false);
            button.setText(issue.title + ": " + issue.button);
            button.setOnClickListener(v -> {
                try {
                    startActivity(issue.action);
                } catch (ActivityNotFoundException | SecurityException e) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                }
            });
            android.widget.LinearLayout.LayoutParams params = (android.widget.LinearLayout.LayoutParams) button.getLayoutParams();
            params.topMargin = margin;
            bgButtons.addView(button, params);
            unverifiable |= issue.unverifiable;
        }
        if (unverifiable) {
            TextView confirm = (TextView) getLayoutInflater().inflate(R.layout.item_tonal_button, bgButtons, false);
            confirm.setText(R.string.bg_confirm);
            confirm.setOnClickListener(v -> {
                BackgroundChecks.confirmUnverifiable(this);
                updateBackground();
            });
            android.widget.LinearLayout.LayoutParams params = (android.widget.LinearLayout.LayoutParams) confirm.getLayoutParams();
            params.topMargin = margin;
            bgButtons.addView(confirm, params);
        }
        bgText.setText(text);
    }

    private void onToggle() {
        if (AppState.phase == AppState.Phase.OFF) {
            start();
        } else {
            startService(new Intent(this, BypassVpnService.class).setAction(BypassVpnService.ACTION_STOP));
        }
    }

    private void onStrategyPicked(String name) {
        Data.setStrategyFor(this, editingKind, name);
        sortConfigs();
        adapter.notifyDataSetChanged();
        // конфиг применяется сразу: смена при перебоях должна быть в одно касание. Выбор для другой сети подождёт её
        if (AppState.phase != AppState.Phase.OFF && editingKind.equals(NetKind.current(this))) {
            start();
        }
    }

    private void start() {
        if (ConnectionMode.isSocks5(this) && !Socks5Settings.isConfigured(this)) {
            Toast.makeText(this, R.string.socks_settings_required, Toast.LENGTH_LONG).show();
            openSocksSettings(false, true);
            return;
        }
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            startActivityForResult(consent, REQUEST_VPN);
        } else {
            startVpn();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SOCKS_SETTINGS) {
            boolean saved = resultCode == RESULT_OK && Socks5Settings.isConfigured(this);
            boolean shouldSelect = selectSocksAfterSettings && saved;
            boolean shouldStart = startAfterSocksSettings && saved;
            selectSocksAfterSettings = false;
            startAfterSocksSettings = false;
            if (shouldSelect) {
                selectMode(ConnectionMode.SOCKS5);
            }
            if (shouldStart) {
                start();
            }
            return;
        }
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                startVpn();
            } else {
                AppState.set(AppState.Phase.OFF, null, getString(R.string.error_vpn_denied));
            }
        }
    }

    private void startVpn() {
        // без этого система и прошивка усыпляют обход: на Xiaomi и похожих телефонах особенно часто
        askBatteryExemption();
        // служба сама берёт конфиг той сети, в которой телефон сейчас
        Intent intent = new Intent(this, BypassVpnService.class).setAction(BypassVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private final class ConfigAdapter extends android.widget.BaseAdapter {
        @Override
        public int getCount() {
            return ConnectionMode.isSocks5(MainActivity.this) ? 0 : visibleNames.size();
        }

        @Override
        public String getItem(int position) {
            return visibleNames.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext()).inflate(R.layout.item_config, parent, false);
            String name = getItem(position);
            boolean chosen = name.equals(selected());
            ((TextView) row.findViewById(R.id.config_name)).setText(name);
            row.findViewById(R.id.config_check).setVisibility(chosen ? View.VISIBLE : View.INVISIBLE);
            row.setBackgroundResource(chosen ? R.drawable.bg_row_selected : R.drawable.bg_row);
            TextView favorite = row.findViewById(R.id.config_favorite);
            boolean isFavorite = Data.isFavorite(MainActivity.this, name);
            favorite.setText(isFavorite ? "★" : "☆");
            favorite.setTextColor(getColor(isFavorite ? R.color.aris_mint : R.color.muted));
            favorite.setContentDescription(getString(isFavorite
                    ? R.string.config_favorite_remove : R.string.config_favorite_add, name));
            favorite.setOnClickListener(v -> toggleFavorite(name));
            TextView detail = row.findViewById(R.id.config_detail);
            String score = Data.scoped(prefs, editingScopes, scope -> Data.scoreKey(scope, name));
            boolean recommended = name.equals(Data.recommended(editingKind));
            if (score != null) {
                String freeze = Data.scoped(prefs, editingScopes, scope -> Data.freezeKey(scope, name));
                String text = getString(recommended ? R.string.config_score_recommended : R.string.config_score, score);
                detail.setText(freeze == null ? text : text + getString(R.string.config_freeze, freeze));
            } else if (recommended) {
                detail.setText(R.string.config_recommended);
            }
            detail.setVisibility(score != null || recommended ? View.VISIBLE : View.GONE);
            return row;
        }
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }
}
