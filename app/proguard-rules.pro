-keep class com.hnedk.dfroot.IReporter { *; }
-keep class com.hnedk.dfroot.SettingsFragment { *; }
-keepclassmembers class * extends android.app.Fragment {
    public <init>();
}
-keep public class * extends android.preference.PreferenceFragment {
    public <init>();
}
