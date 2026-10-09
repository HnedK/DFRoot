package com.hnedk.dfroot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements IReporter {

    private static final String TAG = "dfroot";

    private Button btnRun;
    private ScrollView outputScroll;
    private TextView outputView;
    private Spinner spinnerSuManager;
    private RadioGroup radioGroupMode;
    private RadioButton radioKernelSU;
    private RadioButton radioShell;
    private TextView tvRootBadge;
    private TextView tvDeviceInfo;
    private TextView tvSelinuxStatus;
    private TextView tvKernelInfo;
    private TextView btnCopyLog;
    private Context mDeCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Executor mExec = Executors.newSingleThreadExecutor();
    private int mValidSuManagerPos = 0;

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
        mMain.post(() -> {
            outputView.append(msg);
            outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDeCtx = createDeviceProtectedStorageContext();
        setContentView(R.layout.activity_main);

        getActionBar().setSubtitle("@hnedk & @diabl0w github/xda");

        btnRun          = findViewById(R.id.btnRun);
        outputScroll    = findViewById(R.id.outputScroll);
        outputView      = findViewById(R.id.outputView);
        spinnerSuManager = findViewById(R.id.spinnerSuManager);
        radioGroupMode  = findViewById(R.id.radioGroupMode);
        radioKernelSU   = findViewById(R.id.radioKernelSU);
        radioShell      = findViewById(R.id.radioShell);
        tvRootBadge     = findViewById(R.id.tvRootBadge);
        tvDeviceInfo    = findViewById(R.id.tvDeviceInfo);
        tvSelinuxStatus = findViewById(R.id.tvSelinuxStatus);
        tvKernelInfo    = findViewById(R.id.tvKernelInfo);
        btnCopyLog      = findViewById(R.id.btnCopyLog);

        if (btnCopyLog != null) {
            btnCopyLog.setOnClickListener(v -> copyLogToClipboard());
        }

        refreshDashboard();

        PackageManager pm = getPackageManager();
        List<SuManagerEntry> entries = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            entries.add(new SuManagerEntry(ai.packageName, pm.getApplicationLabel(ai), pm.getApplicationIcon(ai)));
        }
        entries.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        entries.add(0, new SuManagerEntry(null, "Select a SU Manager", null));

        spinnerSuManager.setAdapter(new SuManagerAdapter(this, entries));

        SharedPreferences prefs = mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);

        // Restore saved SU manager selection.
        String saved = prefs.getString(ExploitRunner.PREF_SU_MANAGER, null);
        boolean savedFound = false;
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i).packageName.equals(saved)) {
                spinnerSuManager.setSelection(i);
                mValidSuManagerPos = i;
                savedFound = true;
                break;
            }
        }
        if (!savedFound && saved != null) {
            prefs.edit().remove(ExploitRunner.PREF_SU_MANAGER).apply();
        }

        // Restore saved run mode.
        String savedMode = prefs.getString(ExploitRunner.PREF_RUN_MODE, ExploitRunner.RUN_MODE_KSU);
        if (ExploitRunner.RUN_MODE_SHELL.equals(savedMode)) {
            radioShell.setChecked(true);
        } else {
            radioKernelSU.setChecked(true);
        }
        applyModeUi(ExploitRunner.RUN_MODE_SHELL.equals(savedMode));

        // Persist mode changes and update UI whenever the user switches.
        radioGroupMode.setOnCheckedChangeListener((group, checkedId) -> {
            boolean isShell = (checkedId == R.id.radioShell);
            prefs.edit().putString(ExploitRunner.PREF_RUN_MODE,
                    isShell ? ExploitRunner.RUN_MODE_SHELL : ExploitRunner.RUN_MODE_KSU).apply();
            applyModeUi(isShell);
            updateRunButton();
        });

        spinnerSuManager.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                SuManagerEntry e = entries.get(pos);
                if (e.packageName == null) return;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(e.packageName, 0);
                    if (!new File(ai.nativeLibraryDir, "libksud.so").exists()) {
                        Toast.makeText(MainActivity.this, "Invalid: 'libksud.so' not found", Toast.LENGTH_SHORT).show();
                        spinnerSuManager.setSelection(mValidSuManagerPos);
                        updateRunButton();
                        return;
                    }
                } catch (PackageManager.NameNotFoundException ex) {
                    Toast.makeText(MainActivity.this, "Invalid: 'libksud.so' not found", Toast.LENGTH_SHORT).show();
                    spinnerSuManager.setSelection(mValidSuManagerPos);
                    updateRunButton();
                    return;
                }
                mValidSuManagerPos = pos;
                prefs.edit().putString(ExploitRunner.PREF_SU_MANAGER, e.packageName).apply();
                updateRunButton();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        updateRunButton();

        btnRun.setOnClickListener(v -> {
            btnRun.setEnabled(false);
            outputView.setText("");
            mExec.execute(this::runExploit);
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_terminal) {
            startActivity(new Intent(this, TerminalActivity.class));
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_copy_log) {
            copyLogToClipboard();
            return true;
        } else if (id == R.id.action_clear_log) {
            outputView.setText("");
            Toast.makeText(this, "Log dibersihkan", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void copyLogToClipboard() {
        String logText = outputView != null ? outputView.getText().toString() : "";
        if (logText.isEmpty()) {
            Toast.makeText(this, "Log masih kosong", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            ClipData clip = ClipData.newPlainText("FragSimulator_Log", logText);
            cm.setPrimaryClip(clip);
            Toast.makeText(this, "Log berhasil disalin ke clipboard!", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshDashboard() {
        if (tvDeviceInfo != null) {
            tvDeviceInfo.setText("Device: " + Build.MANUFACTURER + " " + Build.MODEL);
        }
        if (tvKernelInfo != null) {
            String osRelease = System.getProperty("os.version");
            tvKernelInfo.setText("Kernel: " + (osRelease != null ? osRelease : "Unknown"));
        }

        boolean isHooked = new File("/dev/df").exists();
        boolean isRooted = isHooked
                || new File("/dev/dfm6_shell").exists()
                || new File("/dev/dfm6").exists()
                || new File("/data/local/tmp/su").exists();

        if (tvRootBadge != null) {
            if (isRooted) {
                tvRootBadge.setText("● Root Active");
                tvRootBadge.setTextColor(0xFF81C784); // Hijau
            } else {
                tvRootBadge.setText("● Unrooted");
                tvRootBadge.setTextColor(0xFFE57373); // Merah
            }
        }

        if (tvSelinuxStatus != null) {
            tvSelinuxStatus.setText(isHooked ? "SELinux: Permissive" : "SELinux: Enforcing");
        }
    }

    /**
     * Enable/disable UI elements depending on the selected run mode.
     * In Shell mode the SU manager spinner is irrelevant and is grayed out.
     */
    private void applyModeUi(boolean isShell) {
        spinnerSuManager.setEnabled(!isShell);
        spinnerSuManager.setAlpha(isShell ? 0.4f : 1.0f);
    }

    private void updateRunButton() {
        boolean isShell         = radioShell != null && radioShell.isChecked();
        boolean exploitDone     = new File("/dev/df").exists();
        boolean suManagerReady  = mValidSuManagerPos >= 1;
        // Shell mode doesn't need a SU manager; KernelSU mode does.
        btnRun.setEnabled(!exploitDone && (isShell || suManagerReady));
    }

    private void runExploit() {
        String mode = (radioShell != null && radioShell.isChecked())
                      ? ExploitRunner.RUN_MODE_SHELL
                      : ExploitRunner.RUN_MODE_KSU;
        try {
            int rc = ExploitRunner.run(mDeCtx, this, mode);
            final String msg;
            if (ExploitRunner.RUN_MODE_SHELL.equals(mode)) {
                msg = rc == 0 ? "Shell Mode: SUCCESS — Root Daemon & Shizuku Siap!"
                             : "Shell Mode: Error (rc=" + rc + ") — check logcat & dmesg";
            } else {
                msg = rc == 0 ? "KernelSU: SUCCESS"
                    : rc == 1 ? "KernelSU: Error — ksud nonzero exit"
                    : rc == 2 ? "KernelSU: Error — check logcat & dmesg"
                              : "KernelSU: Error — failed to patch files";
            }

            File ksudLog = new File("/data/local/tmp/ksud.log");
            if (ksudLog.exists() && ksudLog.canRead() && ksudLog.length() > 0) {
                try {
                    String logContent = new String(java.nio.file.Files.readAllBytes(ksudLog.toPath()));
                    if (!logContent.isBlank()) {
                        report("\n[ksud log]\n" + logContent.trim() + "\n");
                    }
                } catch (Exception ignored) {}
            }

            mMain.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Log.e(TAG, "exploit exception", e);
            report("\nexception: " + e + "\n");
        } finally {
            mMain.post(() -> {
                updateRunButton();
                refreshDashboard();
            });
        }
    }

    private static class SuManagerEntry {
        final String packageName;
        final CharSequence label;
        final Drawable icon;

        SuManagerEntry(String pkg, CharSequence label, Drawable icon) {
            this.packageName = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private static class SuManagerAdapter extends ArrayAdapter<SuManagerEntry> {
        SuManagerAdapter(Context ctx, List<SuManagerEntry> items) {
            super(ctx, R.layout.item_su_manager, items);
        }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null || v.getTag() != Boolean.FALSE)
                v = LayoutInflater.from(getContext()).inflate(R.layout.item_su_manager_closed, parent, false);
            v.setTag(Boolean.FALSE);
            return bindClosedView(pos, v);
        }

        @Override
        public View getDropDownView(int pos, View v, ViewGroup parent) {
            if (v == null || v.getTag() != Boolean.TRUE)
                v = LayoutInflater.from(getContext()).inflate(R.layout.item_su_manager, parent, false);
            v.setTag(Boolean.TRUE);
            return bindView(pos, v);
        }

        @Override
        public boolean isEnabled(int pos) {
            return getItem(pos).packageName != null;
        }

        private View bindClosedView(int pos, View v) {
            SuManagerEntry e = getItem(pos);
            ImageView icon = v.findViewById(R.id.iconApp);
            TextView label = v.findViewById(R.id.labelApp);
            if (e.packageName == null) {
                icon.setVisibility(View.GONE);
                label.setVisibility(View.VISIBLE);
                label.setText(e.label);
            } else {
                icon.setVisibility(View.VISIBLE);
                label.setVisibility(View.GONE);
                icon.setImageDrawable(e.icon);
            }
            return v;
        }

        private View bindView(int pos, View v) {
            SuManagerEntry e = getItem(pos);
            ((ImageView) v.findViewById(R.id.iconApp)).setImageDrawable(e.icon);
            ((TextView)  v.findViewById(R.id.labelApp)).setText(e.label);
            return v;
        }
    }
}
