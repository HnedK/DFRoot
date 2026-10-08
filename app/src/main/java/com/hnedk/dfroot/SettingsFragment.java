package com.hnedk.dfroot;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;

public class SettingsFragment extends PreferenceFragment {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        Context ctx = getActivity();
        SharedPreferences dePrefs = ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);

        ComponentName bootReceiver = new ComponentName(ctx, BootReceiver.class);
        SwitchPreference bootPref = (SwitchPreference) findPreference("boot_start");
        bootPref.setPersistent(false);
        int state = ctx.getPackageManager().getComponentEnabledSetting(bootReceiver);
        bootPref.setChecked(state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        bootPref.setOnPreferenceChangeListener((pref, value) -> {
            ctx.getPackageManager().setComponentEnabledSetting(bootReceiver,
                    (Boolean) value ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            return true;
        });

        ListPreference bootModePref = (ListPreference) findPreference("boot_mode");
        bootModePref.setPersistent(false);
        String savedBootMode = dePrefs.getString("boot_mode", "ksu");
        bootModePref.setValue(savedBootMode);
        bootModePref.setSummary("Mode aktif: " + ("shell".equals(savedBootMode) ? "Shell" : "KernelSU"));
        bootModePref.setOnPreferenceChangeListener((pref, value) -> {
            String val = (String) value;
            dePrefs.edit().putString("boot_mode", val).apply();
            bootModePref.setSummary("Mode aktif: " + ("shell".equals(val) ? "Shell" : "KernelSU"));
            return true;
        });

        SwitchPreference softRebootPref = (SwitchPreference) findPreference("soft_reboot");
        softRebootPref.setPersistent(false);
        softRebootPref.setChecked(dePrefs.getBoolean("soft_reboot", false));
        softRebootPref.setOnPreferenceChangeListener((pref, value) -> {
            dePrefs.edit().putBoolean("soft_reboot", (Boolean) value).apply();
            return true;
        });

        SwitchPreference disableModulesPref = (SwitchPreference) findPreference("disable_modules");
        disableModulesPref.setPersistent(false);
        disableModulesPref.setChecked(dePrefs.getBoolean("disable_modules", false));
        disableModulesPref.setOnPreferenceChangeListener((pref, value) -> {
            dePrefs.edit().putBoolean("disable_modules", (Boolean) value).apply();
            return true;
        });
    }
}
