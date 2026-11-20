package app.zapret.aris;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Settings for the actual SOCKS5-backed Android VPN route. */
public final class Socks5SettingsActivity extends Activity {
    private EditText host;
    private EditText port;
    private EditText username;
    private EditText password;
    private TextView excludedApp;
    private TextView testStatus;
    private String excludedPackage = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_socks5_settings);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        host = findViewById(R.id.socks_host);
        port = findViewById(R.id.socks_port);
        username = findViewById(R.id.socks_username);
        password = findViewById(R.id.socks_password);
        excludedApp = findViewById(R.id.socks_excluded_app);
        testStatus = findViewById(R.id.socks_test_status);

        Socks5Settings.Endpoint saved = Socks5Settings.load(this);
        host.setText(saved.host);
        port.setText(saved.port > 0 ? Integer.toString(saved.port) : "1080");
        username.setText(saved.username);
        password.setText(saved.password);
        excludedPackage = saved.excludedPackage;
        updateExcludedLabel();

        findViewById(R.id.socks_choose_app).setOnClickListener(v -> chooseExcludedApp());
        findViewById(R.id.socks_test).setOnClickListener(v -> testEndpoint(v));
        findViewById(R.id.socks_save).setOnClickListener(v -> save());
    }

    private Socks5Settings.Endpoint readForm() {
        return Socks5Settings.fromInput(this, host.getText().toString(), port.getText().toString(),
                username.getText().toString(), password.getText().toString(), excludedPackage);
    }

    private void save() {
        try {
            Socks5Settings.save(this, readForm());
            setResult(RESULT_OK);
            finish();
        } catch (IllegalArgumentException e) {
            testStatus.setVisibility(View.VISIBLE);
            testStatus.setText(e.getMessage());
            testStatus.setTextColor(getColor(R.color.danger));
        }
    }

    private void testEndpoint(View button) {
        final Socks5Settings.Endpoint endpoint;
        try {
            endpoint = readForm();
        } catch (IllegalArgumentException e) {
            testStatus.setVisibility(View.VISIBLE);
            testStatus.setText(e.getMessage());
            testStatus.setTextColor(getColor(R.color.danger));
            return;
        }

        button.setEnabled(false);
        testStatus.setVisibility(View.VISIBLE);
        testStatus.setText(R.string.socks_test_running);
        testStatus.setTextColor(getColor(R.color.muted));
        new Thread(() -> {
            String message;
            boolean success;
            try {
                Socks5Probe.check(endpoint);
                message = getString(R.string.socks_test_ok);
                success = true;
            } catch (IOException | RuntimeException e) {
                message = getString(R.string.socks_test_failed, String.valueOf(e.getMessage()));
                success = false;
            }
            String result = message;
            boolean passed = success;
            runOnUiThread(() -> {
                button.setEnabled(true);
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                testStatus.setText(result);
                testStatus.setTextColor(getColor(passed ? R.color.aris_mint : R.color.danger));
            });
        }, "socks5-probe").start();
    }

    private void chooseExcludedApp() {
        TreeMap<String, CharSequence> apps = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        PackageManager manager = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : manager.queryIntentActivities(launcher, 0)) {
            if (info.activityInfo == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (getPackageName().equals(packageName)) {
                continue;
            }
            try {
                ApplicationInfo application = manager.getApplicationInfo(packageName, 0);
                CharSequence label = manager.getApplicationLabel(application);
                apps.putIfAbsent(packageName, label);
            } catch (PackageManager.NameNotFoundException ignored) {
                // application was removed while the chooser was being populated
            }
        }

        List<String> packages = new ArrayList<>(apps.keySet());
        Collections.sort(packages, Comparator.comparing(p -> apps.get(p).toString(), String.CASE_INSENSITIVE_ORDER));
        String[] labels = new String[packages.size() + 1];
        labels[0] = getString(R.string.socks_no_exclusion);
        for (int i = 0; i < packages.size(); i++) {
            labels[i + 1] = apps.get(packages.get(i)) + "  ·  " + packages.get(i);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.socks_excluded_app_title)
                .setItems(labels, (dialog, which) -> {
                    excludedPackage = which == 0 ? "" : packages.get(which - 1);
                    updateExcludedLabel();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateExcludedLabel() {
        if (excludedPackage.isEmpty()) {
            excludedApp.setText(R.string.socks_no_exclusion);
            return;
        }
        try {
            PackageManager manager = getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(excludedPackage, 0);
            excludedApp.setText(manager.getApplicationLabel(info) + "  ·  " + excludedPackage);
        } catch (PackageManager.NameNotFoundException e) {
            excludedPackage = "";
            excludedApp.setText(R.string.socks_no_exclusion);
        }
    }
}
