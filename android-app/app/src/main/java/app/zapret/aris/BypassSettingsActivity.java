package app.zapret.aris;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Настройки обхода из service.bat Windows-версии, которые работают без root. */
public class BypassSettingsActivity extends Activity {
    private static final int REQUEST_EXPORT = 90;
    private static final int REQUEST_IMPORT = 91;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView[] ipsetTabs;
    private TextView ipsetInfo;
    private TextView hostsInfo;
    private View ipsetUpdate;
    private View hostsUpdate;
    private View exportSettings;
    private View importSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bypass_settings);
        SystemBars.apply(this);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        setupFailover();
        exportSettings = findViewById(R.id.settings_export);
        importSettings = findViewById(R.id.settings_import);
        exportSettings.setOnClickListener(v -> chooseExport());
        importSettings.setOnClickListener(v -> confirmImport());

        ipsetTabs = new TextView[]{findViewById(R.id.ipset_none), findViewById(R.id.ipset_loaded), findViewById(R.id.ipset_any)};
        BypassSettings.IpsetMode[] modes = {BypassSettings.IpsetMode.NONE, BypassSettings.IpsetMode.LOADED, BypassSettings.IpsetMode.ANY};
        for (int i = 0; i < ipsetTabs.length; i++) {
            BypassSettings.IpsetMode mode = modes[i];
            ipsetTabs[i].setOnClickListener(v -> {
                BypassSettings.setIpsetMode(this, mode);
                showIpset();
            });
        }
        ipsetInfo = findViewById(R.id.ipset_info);
        ipsetUpdate = findViewById(R.id.ipset_update);
        ipsetUpdate.setOnClickListener(v -> update(true));

        Switch hosts = findViewById(R.id.hosts_enabled);
        hosts.setChecked(BypassSettings.hostsEnabled(this));
        hosts.setOnCheckedChangeListener((button, checked) -> BypassSettings.setHostsEnabled(this, checked));
        hostsInfo = findViewById(R.id.hosts_info);
        hostsUpdate = findViewById(R.id.hosts_update);
        hostsUpdate.setOnClickListener(v -> update(false));

        setupFakes();
        setupTtl(findViewById(R.id.fake_ttl_wifi), NetKind.WIFI);
        setupTtl(findViewById(R.id.fake_ttl_cell), NetKind.CELL);
        showIpset();
        showHosts();
    }


    private void setupFailover() {
        Switch enabled = findViewById(R.id.auto_failover_enabled);
        enabled.setChecked(Data.autoFailoverEnabled(this));
        enabled.setOnCheckedChangeListener((button, checked) -> Data.setAutoFailoverEnabled(this, checked));

        Spinner action = findViewById(R.id.failure_action);
        List<String> labels = java.util.Arrays.asList(
                getString(R.string.failure_action_stop), getString(R.string.failure_action_direct));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        action.setAdapter(adapter);
        action.setSelection(Data.failureAction(this).equals(Data.FAILURE_ACTION_DIRECT) ? 1 : 0, false);
        action.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Data.setFailureAction(BypassSettingsActivity.this,
                        position == 1 ? Data.FAILURE_ACTION_DIRECT : Data.FAILURE_ACTION_STOP);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void chooseExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "zapret-aris-settings.json");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    private void confirmImport() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_import_confirm_title)
                .setMessage(R.string.settings_import_confirm)
                .setPositiveButton(R.string.settings_import, (dialog, which) -> chooseImport())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void chooseImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json");
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) {
            worker.execute(() -> exportFile(uri));
        } else if (requestCode == REQUEST_IMPORT) {
            worker.execute(() -> importFile(uri));
        }
    }

    private void exportFile(Uri uri) {
        String message;
        try {
            byte[] contents = SettingsTransfer.export(this);
            try (java.io.OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) {
                    throw new IOException("не удалось открыть файл для записи");
                }
                output.write(contents);
                output.flush();
            }
            message = getString(R.string.settings_export_done);
        } catch (IOException | org.json.JSONException | RuntimeException e) {
            message = getString(R.string.settings_transfer_failed, String.valueOf(e.getMessage()));
        }
        String result = message;
        main.post(() -> {
            if (!isFinishing() && !isDestroyed()) {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void importFile(Uri uri) {
        String message;
        boolean imported = false;
        try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("не удалось открыть файл");
            }
            SettingsTransfer.importFrom(this, input);
            imported = true;
            message = getString(R.string.settings_import_done);
        } catch (IOException | org.json.JSONException | RuntimeException e) {
            message = getString(R.string.settings_transfer_failed, String.valueOf(e.getMessage()));
        }
        String result = message;
        boolean restart = imported;
        main.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            Toast.makeText(this, result, Toast.LENGTH_LONG).show();
            if (restart) {
                applyImportedSettings();
                finish();
            }
        });
    }

    private void applyImportedSettings() {
        UserLists.consumeChanged();
        BypassSettings.consumeChanged();
        ConnectionMode.consumeChanged();
        Socks5Settings.consumeChanged();
        if (AppState.phase != AppState.Phase.OFF) {
            Intent intent = new Intent(this, BypassVpnService.class).setAction(BypassVpnService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        }
        if (TgProxySettings.prefs(this).getBoolean(TgProxySettings.ENABLED, false)) {
            TelegramProxyService.restart(this);
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void setupFakes() {
        Spinner spinner = findViewById(R.id.discord_fake);
        List<String> files;
        try {
            files = BypassSettings.fakeChoices(this);
        } catch (IOException e) {
            files = new ArrayList<>();
            files.add(BypassSettings.DISCORD_FAKE_DEFAULT);
        }
        List<String> labels = new ArrayList<>();
        for (String file : files) {
            labels.add(file.equals(BypassSettings.DISCORD_FAKE_DEFAULT)
                    ? getString(R.string.fake_default) : file.substring(0, file.length() - ".bin".length()));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int current = files.indexOf(BypassSettings.discordFake(this));
        spinner.setSelection(Math.max(current, 0), false);
        List<String> choices = files;
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!choices.get(position).equals(BypassSettings.discordFake(BypassSettingsActivity.this))) {
                    BypassSettings.setDiscordFake(BypassSettingsActivity.this, choices.get(position));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupTtl(Spinner spinner, String kind) {
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.fake_ttl_auto));
        for (int ttl : BypassSettings.FAKE_TTL_CHOICES) {
            labels.add(Integer.toString(ttl));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int current = BypassSettings.fakeTtl(this, kind);
        spinner.setSelection(Math.max(labels.indexOf(Integer.toString(current)), 0), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int ttl = position == 0 ? 0 : BypassSettings.FAKE_TTL_CHOICES[position - 1];
                if (ttl != BypassSettings.fakeTtl(BypassSettingsActivity.this, kind)) {
                    BypassSettings.setFakeTtl(BypassSettingsActivity.this, kind, ttl);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void showIpset() {
        BypassSettings.IpsetMode mode = BypassSettings.ipsetMode(this);
        for (int i = 0; i < ipsetTabs.length; i++) {
            ipsetTabs[i].setSelected(BypassSettings.IpsetMode.values()[i] == mode);
        }
        int hint = mode == BypassSettings.IpsetMode.NONE ? R.string.ipset_none_hint
                : mode == BypassSettings.IpsetMode.LOADED ? R.string.ipset_loaded_hint : R.string.ipset_any_hint;
        ((TextView) findViewById(R.id.ipset_hint)).setText(hint);
        worker.execute(() -> {
            int count = BypassSettings.ipsetCount(this);
            String when = when(BypassSettings.ipsetFile(this).lastModified());
            main.post(() -> ipsetInfo.setText(getString(R.string.ipset_info, count, when)));
        });
    }

    private void showHosts() {
        worker.execute(() -> {
            int count = BypassSettings.hostsCount(this);
            String when = when(BypassSettings.hostsFile(this).lastModified());
            main.post(() -> hostsInfo.setText(getString(R.string.hosts_info, count, when)));
        });
    }

    private String when(long modified) {
        return modified > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(modified))
                : getString(R.string.list_builtin);
    }

    private void update(boolean ipset) {
        View button = ipset ? ipsetUpdate : hostsUpdate;
        button.setEnabled(false);
        Toast.makeText(this, R.string.list_updating, Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            String message;
            try {
                int count = ipset ? BypassSettings.updateIpset(this) : BypassSettings.updateHosts(this);
                message = getString(R.string.list_updated, count);
            } catch (IOException | RuntimeException e) {
                message = getString(R.string.list_update_failed, String.valueOf(e.getMessage()));
            }
            String text = message;
            main.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                button.setEnabled(true);
                Toast.makeText(this, text, Toast.LENGTH_LONG).show();
                if (ipset) {
                    showIpset();
                } else {
                    showHosts();
                }
            });
        });
    }
}
