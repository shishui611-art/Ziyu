# Ziyu uses no reflection-based serialization framework. Keep rules are
# intentionally minimal; Android/Compose consumer rules are supplied by their
# dependencies.
-keep public class io.github.xgl34222220.ziyu.xposed.WebFontHook { *; }
-keep public class io.github.xgl34222220.ziyu.xposed.GoogleSansHook { *; }
-dontwarn de.robv.android.xposed.**
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
