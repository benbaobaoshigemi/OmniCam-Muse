# Hook code is entered by name from assets/xposed_init and runs inside other apps;
# keep it unobfuscated so logs and stack traces stay readable.
-keep class local.jiege.hook.** { *; }
-dontwarn de.robv.android.xposed.**
