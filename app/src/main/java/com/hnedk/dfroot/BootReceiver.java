package com.hnedk.dfroot;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;

public class BootReceiver extends BroadcastReceiver implements IReporter {
    private static final String TAG = "dfroot";
    private static final String PREF_BOOT_COUNT = "boot_crash_count";
    private static final String PREF_LAST_BOOT_TIME = "last_boot_time";

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (new File("/dev/df").exists()) {
            Log.i(TAG, "boot: already hooked, skipping");
            return;
        }
        Log.i(TAG, "boot: " + intent.getAction());
        final Context deCtx = context.createDeviceProtectedStorageContext();
        final SharedPreferences dePrefs = deCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);

        // --- Proteksi Anti-Bootloop ---
        long now = System.currentTimeMillis();
        long lastBoot = dePrefs.getLong(PREF_LAST_BOOT_TIME, 0);
        int bootCount = dePrefs.getInt(PREF_BOOT_COUNT, 0);

        // Jika reboot terjadi dalam waktu kurang dari 10 menit (600.000 ms)
        if (now - lastBoot < 10 * 60 * 1000) {
            bootCount++;
        } else {
            // Jika sudah lebih dari 10 menit tanpa crash/reboot, reset hitungan
            bootCount = 1;
        }

        dePrefs.edit()
                .putLong(PREF_LAST_BOOT_TIME, now)
                .putInt(PREF_BOOT_COUNT, bootCount)
                .apply();

        // Jika reboot berulang sudah mencapai 3x dalam 10 menit, MATIKAN auto start on boot!
        if (bootCount >= 3) {
            Log.w(TAG, "boot: TERDETEKSI 3X REBOOT BERUNTUN DALAM 10 MENIT! Mematikan BootReceiver untuk mencegah bootloop.");
            ComponentName receiver = new ComponentName(context, BootReceiver.class);
            context.getPackageManager().setComponentEnabledSetting(
                    receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            );
            dePrefs.edit().putInt(PREF_BOOT_COUNT, 0).apply();
            return;
        }

        // Ambil mode yang dipilih di settings: "ksu" atau "shell"
        final String bootMode = dePrefs.getString("boot_mode", ExploitRunner.RUN_MODE_KSU);

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dfroot:boot");
        wl.acquire();
        new Thread(() -> {
            try {
                int rc = ExploitRunner.run(deCtx, this, bootMode);
                Log.i(TAG, "boot (" + bootMode + "): exploit rc=" + rc);
            } catch (Exception e) {
                Log.e(TAG, "boot: exploit exception", e);
            } finally {
                wl.release();
            }
        }, "dfroot-boot").start();
    }
}
