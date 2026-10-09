package com.hnedk.dfroot;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements IReporter {

    private static final String TAG = "dfroot";

    private ImageButton btnSettings;
    private ImageButton btnToolbox;
    private ImageButton btnTerminal;
    private TextView tvDeviceInfo;
    private TextView tvKernelInfo;
    private TextView tvSelinuxStatus;
    private TextView tvRootBadge;

    private TextView tvStepIndicator;
    private ProgressBar progressBar;
    private TextView tvStepDesc;
    private Button btnRun;

    /* Bottom Sheet elements */
    private FrameLayout bottomSheetContainer;
    private View sheetOverlay;
    private View optKernelSU;
    private View optShell;
    private RadioButton radioKernelSU;
    private RadioButton radioShell;
    private View boxManagerInfo;
    private TextView tvDetectedManager;
    private Button btnSheetCancel;
    private Button btnSheetConfirm;

    private Context mDeCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Executor mExec = Executors.newSingleThreadExecutor();
    private String mDetectedSuManager = null;

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
        String cleanMsg = msg.trim();
        mMain.post(() -> {
            if (cleanMsg.isEmpty()) return;
            tvStepDesc.setText(cleanMsg);
            if (cleanMsg.contains("Staging custom ksud") || cleanMsg.contains("Staging bundled")) {
                animateProgress(25);
                tvStepIndicator.setText("[1/4]");
            } else if (cleanMsg.contains("sp=0x") || cleanMsg.contains("skb=") || cleanMsg.contains("heap")) {
                animateProgress(50);
                tvStepIndicator.setText("[2/4]");
            } else if (cleanMsg.contains("bypass") || cleanMsg.contains("Samsung") || cleanMsg.contains("LKM")) {
                animateProgress(75);
                tvStepIndicator.setText("[3/4]");
            } else if (cleanMsg.contains("manager") || cleanMsg.contains("daemon")) {
                animateProgress(90);
                tvStepIndicator.setText("[4/4]");
            }
        });
    }

    private void animateProgress(int target) {
        ObjectAnimator anim = ObjectAnimator.ofInt(progressBar, "progress", progressBar.getProgress(), target);
        anim.setDuration(250);
        anim.start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDeCtx = createDeviceProtectedStorageContext();
        setContentView(R.layout.activity_main);

        btnSettings          = findViewById(R.id.btnSettings);
        btnToolbox           = findViewById(R.id.btnToolbox);
        btnTerminal          = findViewById(R.id.btnTerminal);
        tvDeviceInfo         = findViewById(R.id.tvDeviceInfo);
        tvKernelInfo         = findViewById(R.id.tvKernelInfo);
        tvSelinuxStatus      = findViewById(R.id.tvSelinuxStatus);
        tvRootBadge          = findViewById(R.id.tvRootBadge);

        tvStepIndicator      = findViewById(R.id.tvStepIndicator);
        progressBar          = findViewById(R.id.progressBar);
        tvStepDesc           = findViewById(R.id.tvStepDesc);
        btnRun               = findViewById(R.id.btnRun);

        bottomSheetContainer = findViewById(R.id.bottomSheetContainer);
        sheetOverlay         = findViewById(R.id.sheetOverlay);
        optKernelSU          = findViewById(R.id.optKernelSU);
        optShell             = findViewById(R.id.optShell);
        radioKernelSU        = findViewById(R.id.radioKernelSU);
        radioShell           = findViewById(R.id.radioShell);
        boxManagerInfo       = findViewById(R.id.boxManagerInfo);
        tvDetectedManager    = findViewById(R.id.tvDetectedManager);
        btnSheetCancel       = findViewById(R.id.btnSheetCancel);
        btnSheetConfirm      = findViewById(R.id.btnSheetConfirm);

        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnToolbox.setOnClickListener(v -> startActivity(new Intent(this, ToolboxActivity.class)));
        btnTerminal.setOnClickListener(v -> startActivity(new Intent(this, TerminalActivity.class)));

        detectInstalledSuManager();
        refreshDashboard();

        SharedPreferences prefs = mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        String savedMode = prefs.getString(ExploitRunner.PREF_RUN_MODE, ExploitRunner.RUN_MODE_KSU);
        selectSheetMode(ExploitRunner.RUN_MODE_SHELL.equals(savedMode) ? "shell" : "ksu");

        optKernelSU.setOnClickListener(v -> selectSheetMode("ksu"));
        optShell.setOnClickListener(v -> selectSheetMode("shell"));

        btnRun.setOnClickListener(v -> showBottomSheet());
        sheetOverlay.setOnClickListener(v -> hideBottomSheet());
        btnSheetCancel.setOnClickListener(v -> hideBottomSheet());

        btnSheetConfirm.setOnClickListener(v -> {
            hideBottomSheet();
            btnRun.setEnabled(false);
            progressBar.setProgress(0);
            tvStepIndicator.setText("[0/4]");
            tvStepDesc.setText("Initiating exploit sequence...");
            mExec.execute(this::runExploit);
        });
    }

    private void detectInstalledSuManager() {
        PackageManager pm = getPackageManager();
        String[] preferred = { "me.weishu.kernelsu", "io.github.vvb2060.magisk" };
        for (String pkg : preferred) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if (ai != null) {
                    mDetectedSuManager = pkg;
                    break;
                }
            } catch (Exception ignored) {}
        }
        if (mDetectedSuManager == null) {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (ai.packageName.toLowerCase().contains("kernelsu") ||
                    ai.packageName.toLowerCase().contains("ksu")) {
                    mDetectedSuManager = ai.packageName;
                    break;
                }
            }
        }
        if (mDetectedSuManager == null) {
            mDetectedSuManager = "me.weishu.kernelsu";
        }
        tvDetectedManager.setText(mDetectedSuManager);

        SharedPreferences prefs = mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(ExploitRunner.PREF_SU_MANAGER, mDetectedSuManager).apply();
    }

    private void showBottomSheet() {
        bottomSheetContainer.setVisibility(View.VISIBLE);
    }

    private void hideBottomSheet() {
        bottomSheetContainer.setVisibility(View.GONE);
    }

    private void selectSheetMode(String mode) {
        boolean isKsu = "ksu".equals(mode);
        radioKernelSU.setChecked(isKsu);
        radioShell.setChecked(!isKsu);
        optKernelSU.setBackgroundResource(isKsu ? R.drawable.bg_df_option_card_selected : R.drawable.bg_df_option_card);
        optShell.setBackgroundResource(!isKsu ? R.drawable.bg_df_option_card_selected : R.drawable.bg_df_option_card);
        boxManagerInfo.setVisibility(isKsu ? View.VISIBLE : View.GONE);

        SharedPreferences prefs = mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(ExploitRunner.PREF_RUN_MODE, isKsu ? ExploitRunner.RUN_MODE_KSU : ExploitRunner.RUN_MODE_SHELL).apply();
    }

    private void refreshDashboard() {
        if (tvDeviceInfo != null) {
            tvDeviceInfo.setText(Build.MANUFACTURER + " " + Build.MODEL);
        }
        if (tvKernelInfo != null) {
            String osRelease = System.getProperty("os.version");
            tvKernelInfo.setText(osRelease != null ? osRelease : "Unknown");
        }

        boolean isHooked = new File("/dev/df").exists();
        boolean isRooted = isHooked
                || new File("/dev/dfm6_shell").exists()
                || new File("/dev/dfm6").exists()
                || new File("/data/local/tmp/su").exists();

        if (tvRootBadge != null) {
            if (isRooted) {
                tvRootBadge.setText("Root Active");
                tvRootBadge.setBackgroundResource(R.drawable.bg_df_tag_active);
                tvRootBadge.setTextColor(getColor(R.color.df_status_success_text));
                btnRun.setText("RE-ROOT");
            } else {
                tvRootBadge.setText("Unrooted");
                tvRootBadge.setBackgroundResource(R.drawable.bg_df_tag_idle);
                tvRootBadge.setTextColor(getColor(R.color.df_text_body));
                btnRun.setText("ROOT");
            }
        }

        if (tvSelinuxStatus != null) {
            tvSelinuxStatus.setText(isHooked ? "Permissive" : "Enforcing");
        }
    }

    private void runExploit() {
        SharedPreferences prefs = mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        String mode = prefs.getString(ExploitRunner.PREF_RUN_MODE, ExploitRunner.RUN_MODE_KSU);
        try {
            int rc = ExploitRunner.run(mDeCtx, this, mode);
            final String msg;
            if (ExploitRunner.RUN_MODE_SHELL.equals(mode)) {
                msg = rc == 0 ? "Shell Mode: SUCCESS (Port 1337 ready)"
                              : "Shell Mode: Error (rc=" + rc + ")";
            } else {
                msg = rc == 0 ? "KernelSU: SUCCESS"
                              : "KernelSU: Error (rc=" + rc + ")";
            }

            mMain.post(() -> {
                if (rc == 0) {
                    animateProgress(100);
                    tvStepIndicator.setText("[Done]");
                    tvStepDesc.setText(ExploitRunner.RUN_MODE_KSU.equals(mode)
                            ? "Jailbreak complete. Opening KernelSU..."
                            : "Shell mode ready on 127.0.0.1:1337");
                } else {
                    tvStepIndicator.setText("[Failed]");
                    tvStepDesc.setText("Exploit exited with code " + rc);
                }
            });

            if (rc == 0 && ExploitRunner.RUN_MODE_KSU.equals(mode)) {
                mMain.postDelayed(() -> {
                    try {
                        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(mDetectedSuManager);
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(launchIntent);
                        }
                    } catch (Exception ignored) {}
                }, 3500);
            }

            mMain.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Log.e(TAG, "exploit exception", e);
            mMain.post(() -> {
                tvStepIndicator.setText("[Error]");
                tvStepDesc.setText(e.getMessage());
            });
        } finally {
            mMain.post(() -> {
                btnRun.setEnabled(true);
                refreshDashboard();
            });
        }
    }
}
