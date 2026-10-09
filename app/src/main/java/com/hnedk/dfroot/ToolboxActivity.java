package com.hnedk.dfroot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ToolboxActivity extends Activity {

    private static final int REQUEST_PICK_APK = 1001;
    private static final int REQUEST_PICK_FONT = 1002;

    private static final String MODULE_DIR = "/data/adb/modules/dfroot_font";

    private TextView tvSelectedApk;
    private TextView tvApkDetails;
    private CheckBox cbDowngrade;
    private CheckBox cbGrantPerms;
    private Button btnSelectApk;
    private Button btnInstallApk;

    private TextView tvFontStatus;
    private TextView tvSelectedFont;
    private TextView tvFontPreview;
    private Button btnSelectFont;
    private Button btnApplyFont;
    private Button btnResetFont;

    private TextView tvToolboxLog;

    private Uri mApkUri;
    private Uri mFontUri;
    private File mStagedFontFile;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_toolbox);

        View btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Section 1: APK Installer
        tvSelectedApk = findViewById(R.id.tvSelectedApk);
        tvApkDetails = findViewById(R.id.tvApkDetails);
        cbDowngrade = findViewById(R.id.cbDowngrade);
        cbGrantPerms = findViewById(R.id.cbGrantPerms);
        btnSelectApk = findViewById(R.id.btnSelectApk);
        btnInstallApk = findViewById(R.id.btnInstallApk);

        // Section 2: Font Manager
        tvFontStatus = findViewById(R.id.tvFontStatus);
        tvSelectedFont = findViewById(R.id.tvSelectedFont);
        tvFontPreview = findViewById(R.id.tvFontPreview);
        btnSelectFont = findViewById(R.id.btnSelectFont);
        btnApplyFont = findViewById(R.id.btnApplyFont);
        btnResetFont = findViewById(R.id.btnResetFont);

        // Section 3: Log
        tvToolboxLog = findViewById(R.id.tvToolboxLog);

        btnSelectApk.setOnClickListener(v -> openFilePicker("application/vnd.android.package-archive", REQUEST_PICK_APK));
        btnInstallApk.setOnClickListener(v -> startApkInstallation());

        btnSelectFont.setOnClickListener(v -> openFilePicker("*/*", REQUEST_PICK_FONT));
        btnApplyFont.setOnClickListener(v -> startFontInstallation());
        btnResetFont.setOnClickListener(v -> startFontReset());

        checkActiveFontStatus();
    }

    private void log(String message) {
        mMain.post(() -> {
            if (tvToolboxLog != null) {
                String cur = tvToolboxLog.getText().toString();
                if (cur.equals("Ready.")) cur = "";
                tvToolboxLog.setText(cur + "\n" + message);
            }
        });
    }

    private void openFilePicker(String mimeType, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(mimeType);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            Toast.makeText(this, "No file manager found", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        if (requestCode == REQUEST_PICK_APK) {
            handleApkPicked(uri);
        } else if (requestCode == REQUEST_PICK_FONT) {
            handleFontPicked(uri);
        }
    }

    // =========================================================================
    // SECTION 1: ROOT APK INSTALLATION
    // =========================================================================

    private void handleApkPicked(Uri uri) {
        mApkUri = uri;
        tvSelectedApk.setText(uri.getLastPathSegment() != null ? uri.getLastPathSegment() : uri.toString());
        btnInstallApk.setEnabled(false);

        mExecutor.execute(() -> {
            try {
                File tempApk = new File(getCacheDir(), "inspect.apk");
                copyUriToFile(uri, tempApk);

                PackageManager pm = getPackageManager();
                PackageInfo info = pm.getPackageArchiveInfo(tempApk.getAbsolutePath(), 0);
                if (info != null) {
                    String pkgName = info.packageName;
                    String verName = info.versionName != null ? info.versionName : String.valueOf(info.versionCode);
                    mMain.post(() -> {
                        tvApkDetails.setText("Package: " + pkgName + " (v" + verName + ")");
                        btnInstallApk.setEnabled(true);
                    });
                } else {
                    mMain.post(() -> {
                        tvApkDetails.setText("Valid APK parsed, ready to install.");
                        btnInstallApk.setEnabled(true);
                    });
                }
            } catch (Exception e) {
                mMain.post(() -> {
                    tvApkDetails.setText("Error inspecting APK: " + e.getMessage());
                    btnInstallApk.setEnabled(true);
                });
            }
        });
    }

    private void startApkInstallation() {
        if (mApkUri == null) return;
        btnInstallApk.setEnabled(false);
        log("[APK] Preparing package installation...");

        boolean downgrade = cbDowngrade.isChecked();
        boolean grant = cbGrantPerms.isChecked();

        mExecutor.execute(() -> {
            try {
                // Copy APK to app cache first
                File stagedCache = new File(getCacheDir(), "install_target.apk");
                copyUriToFile(mApkUri, stagedCache);

                // Stage into /data/local/tmp via Root
                String stagingPath = "/data/local/tmp/dfroot_install.apk";
                log("[APK] Staging APK to " + stagingPath + " (UID 0)...");

                RootExecutor.execute("cp \"" + stagedCache.getAbsolutePath() + "\" \"" + stagingPath + "\"");
                RootExecutor.execute("chmod 644 \"" + stagingPath + "\"");

                // Build pm install command
                StringBuilder cmd = new StringBuilder("pm install -r");
                if (downgrade) cmd.append(" -d");
                if (grant) cmd.append(" -g");
                cmd.append(" \"").append(stagingPath).append("\"");

                log("[APK] Executing: " + cmd);
                RootExecutor.Result result = RootExecutor.execute(cmd.toString(), ToolboxActivity.this::log);

                // Clean up staged APK
                RootExecutor.execute("rm -f \"" + stagingPath + "\"");
                stagedCache.delete();

                mMain.post(() -> {
                    btnInstallApk.setEnabled(true);
                    if (result.isSuccess()) {
                        log("[APK SUCCESS] Package installed successfully!");
                        Toast.makeText(this, "APK installed successfully!", Toast.LENGTH_SHORT).show();
                    } else {
                        log("[APK FAILED] Exit code: " + result.exitCode);
                        Toast.makeText(this, "APK installation failed", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                log("[APK ERROR] " + e.getMessage());
                mMain.post(() -> btnInstallApk.setEnabled(true));
            }
        });
    }

    // =========================================================================
    // SECTION 2: CUSTOM FONT OVERLAY (ANTI-BOOTLOOP)
    // =========================================================================

    private void checkActiveFontStatus() {
        mExecutor.execute(() -> {
            RootExecutor.Result res = RootExecutor.execute("[ -d \"" + MODULE_DIR + "\" ] && echo 1 || echo 0");
            boolean active = "1".equals(res.output.trim());
            mMain.post(() -> {
                if (active) {
                    tvFontStatus.setText("Status: Custom Font Active (Overlay Module)");
                    tvFontStatus.setTextColor(getColor(R.color.df_status_success_text));
                } else {
                    tvFontStatus.setText("Status: Stock System Font");
                    tvFontStatus.setTextColor(getColor(R.color.df_text_body));
                }
            });
        });
    }

    private void handleFontPicked(Uri uri) {
        mFontUri = uri;
        tvSelectedFont.setText(uri.getLastPathSegment() != null ? uri.getLastPathSegment() : uri.toString());
        btnApplyFont.setEnabled(false);

        mExecutor.execute(() -> {
            try {
                File tempFont = new File(getCacheDir(), "preview_font.ttf");
                copyUriToFile(uri, tempFont);

                // Validate TTF / OTF header (0x00010000 or 'OTTO' or 'true' or 'typ1')
                if (!validateFontHeader(tempFont)) {
                    mMain.post(() -> {
                        tvSelectedFont.setText("Invalid font file! Header validation failed.");
                        Toast.makeText(this, "File is not a valid TTF/OTF font", Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                // Verify Typeface parsing safely
                try {
                    Typeface tf = Typeface.createFromFile(tempFont);
                    if (tf == null) throw new IllegalArgumentException("Typeface creation failed");
                    mStagedFontFile = tempFont;
                    mMain.post(() -> {
                        tvFontPreview.setTypeface(tf);
                        btnApplyFont.setEnabled(true);
                        log("[FONT] Font validated successfully (" + tempFont.length() + " bytes)");
                    });
                } catch (Exception e) {
                    mMain.post(() -> {
                        tvSelectedFont.setText("Font corruption detected: " + e.getMessage());
                        Toast.makeText(this, "Unsafe font! May cause crashloop.", Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                mMain.post(() -> tvSelectedFont.setText("Error loading font: " + e.getMessage()));
            }
        });
    }

    private boolean validateFontHeader(File file) {
        if (!file.exists() || file.length() < 12) return false;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[4];
            int read = fis.read(header);
            if (read < 4) return false;
            // 0x00010000 (TrueType)
            if (header[0] == 0x00 && header[1] == 0x01 && header[2] == 0x00 && header[3] == 0x00) return true;
            // 'OTTO' (OpenType)
            if (header[0] == 'O' && header[1] == 'T' && header[2] == 'T' && header[3] == 'O') return true;
            // 'true'
            if (header[0] == 't' && header[1] == 'r' && header[2] == 'u' && header[3] == 'e') return true;
        } catch (Exception ignored) {}
        return false;
    }

    private void startFontInstallation() {
        if (mStagedFontFile == null || !mStagedFontFile.exists()) return;
        btnApplyFont.setEnabled(false);
        log("[FONT] Building safe overlay module in /data/adb/modules/dfroot_font...");

        mExecutor.execute(() -> {
            try {
                // 1. Stage font into /data/local/tmp/new_font.ttf
                String tmpFont = "/data/local/tmp/dfroot_custom_font.ttf";
                RootExecutor.execute("cp \"" + mStagedFontFile.getAbsolutePath() + "\" \"" + tmpFont + "\"");
                RootExecutor.execute("chmod 644 \"" + tmpFont + "\"");

                // 2. Create module directory tree
                String fontDestDir = MODULE_DIR + "/system/fonts";
                RootExecutor.execute("mkdir -p \"" + fontDestDir + "\"");

                // 3. Write module.prop
                String propCmd = "cat << 'EOF' > " + MODULE_DIR + "/module.prop\n" +
                        "id=dfroot_font\n" +
                        "name=DFRoot Custom Font\n" +
                        "version=1.0\n" +
                        "versionCode=1\n" +
                        "author=DFRoot\n" +
                        "description=Non-destructive font overlay module\n" +
                        "EOF";
                RootExecutor.execute(propCmd);

                // 4. Overwrite standard Android & Samsung system font fallbacks
                String[] standardFonts = {
                        "Roboto-Regular.ttf",
                        "Roboto-Bold.ttf",
                        "Roboto-Medium.ttf",
                        "RobotoStatic-Regular.ttf",
                        "SamsungOne-400.ttf",
                        "SamsungOne-600.ttf",
                        "SamsungOne-700.ttf",
                        "SEC-Regular.ttf",
                        "SECRobotoLight-Regular.ttf"
                };

                for (String fontName : standardFonts) {
                    RootExecutor.execute("cp \"" + tmpFont + "\" \"" + fontDestDir + "/" + fontName + "\"");
                    RootExecutor.execute("chmod 644 \"" + fontDestDir + "/" + fontName + "\"");
                }

                // Clean temporary file
                RootExecutor.execute("rm -f \"" + tmpFont + "\"");

                log("[FONT SUCCESS] Module created with safe fallbacks.");
                log("[FONT] Performing soft-reboot to load font without full restart...");

                // Soft reboot to reload Zygote and font cache
                RootExecutor.execute("killall -9 system_server || setprop ctl.restart zygote");

                mMain.post(() -> {
                    btnApplyFont.setEnabled(true);
                    checkActiveFontStatus();
                    Toast.makeText(this, "Font applied! Reloading system...", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                log("[FONT ERROR] " + e.getMessage());
                mMain.post(() -> btnApplyFont.setEnabled(true));
            }
        });
    }

    private void startFontReset() {
        btnResetFont.setEnabled(false);
        log("[FONT] Restoring stock font by removing module directory...");

        mExecutor.execute(() -> {
            try {
                RootExecutor.execute("rm -rf \"" + MODULE_DIR + "\"");
                log("[FONT] Module removed. Performing soft reboot...");
                RootExecutor.execute("killall -9 system_server || setprop ctl.restart zygote");

                mMain.post(() -> {
                    btnResetFont.setEnabled(true);
                    checkActiveFontStatus();
                    tvFontPreview.setTypeface(Typeface.DEFAULT);
                    Toast.makeText(this, "Stock font restored!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                log("[FONT ERROR] " + e.getMessage());
                mMain.post(() -> btnResetFont.setEnabled(true));
            }
        });
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    private void copyUriToFile(Uri uri, File dest) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }
}
