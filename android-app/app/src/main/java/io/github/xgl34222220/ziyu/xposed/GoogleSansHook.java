package io.github.xgl34222220.ziyu.xposed;

import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.database.MatrixCursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.FontsContract;
import android.provider.FontRequest;
import java.io.File;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Intercepts Google Sans / Product Sans loading (GMS downloadable queries and APK-bundled font
 * resources/assets/files) inside user-selected apps in LSPosed and redirects to system sans-serif.
 */
@SuppressWarnings("deprecation")
public final class GoogleSansHook implements IXposedHookLoadPackage {
    private static final String GMS_FONTS_AUTHORITY = "com.google.android.gms.fonts";
    private static final Pattern GOOGLE_SANS_PATTERN = Pattern.compile(
        "(?i)(?:google[\\s_-]*sans|product[\\s_-]*sans|(?:^|[/\\\\._-])gs[\\s_-]*(?:text|display|flex|regular|medium|semibold|bold|light|thin)(?:$|[/\\\\._-]))"
    );
    private static final Pattern EXCLUDED_PATTERN = Pattern.compile(
        "(?i)(?:code|mono|symbol|icon|emoji|clock|math|dingbat)"
    );
    private static final Pattern NUMERIC_WEIGHT_PATTERN = Pattern.compile(
        "(?:^|[._\\s-])([1-9]00)(?:$|[._\\s-])"
    );
    private static final ThreadLocal<Boolean> IN_HOOK = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Map<Object, BuilderOverride> BUILDER_OVERRIDES =
        Collections.synchronizedMap(new WeakHashMap<>());

    private static final class BuilderOverride {
        int weight;
        boolean italic;

        BuilderOverride(int weight, boolean italic) {
            this.weight = weight;
            this.italic = italic;
        }
    }

    static boolean isGoogleSansTarget(String text) {
        if (text == null || text.isEmpty()) return false;
        if (EXCLUDED_PATTERN.matcher(text).find()) return false;
        return GOOGLE_SANS_PATTERN.matcher(text).find();
    }

    static boolean isGmsFontCachePath(String path) {
        if (path == null || path.isEmpty()) return false;
        if (EXCLUDED_PATTERN.matcher(path).find()) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/com.google.android.gms/files/fonts/")
            || lower.contains("/com.android.vending/files/fonts/");
    }

    static int inferWeight(String hint, int style) {
        if (hint != null) {
            String lower = hint.toLowerCase(Locale.ROOT);
            Matcher m = NUMERIC_WEIGHT_PATTERN.matcher(lower);
            if (m.find()) {
                try {
                    int w = Integer.parseInt(m.group(1));
                    if (w >= 100 && w <= 900) return w;
                } catch (NumberFormatException ignored) { }
            }
            if (lower.contains("black") || lower.contains("heavy")) return 900;
            if (lower.contains("extrabold") || lower.contains("extra_bold") || lower.contains("extra-bold") || lower.contains("ultrabold")) return 800;
            if (lower.contains("semibold") || lower.contains("semi_bold") || lower.contains("semi-bold") || lower.contains("demibold")) return 600;
            if (lower.contains("bold")) return 700;
            if (lower.contains("medium")) return 500;
            if (lower.contains("extralight") || lower.contains("extra_light") || lower.contains("extra-light") || lower.contains("ultralight")) return 200;
            if (lower.contains("light")) return 300;
            if (lower.contains("thin") || lower.contains("hairline")) return 100;
        }
        return (style & Typeface.BOLD) != 0 ? 700 : 400;
    }

    static boolean inferItalic(String hint, int style) {
        if ((style & Typeface.ITALIC) != 0) return true;
        return hint != null && hint.toLowerCase(Locale.ROOT).contains("italic");
    }

    private static Typeface systemSans(int weight, boolean italic) {
        int clamped = Math.max(100, Math.min(900, weight));
        Boolean prev = IN_HOOK.get();
        IN_HOOK.set(Boolean.TRUE);
        try {
            return Typeface.create(Typeface.SANS_SERIF, clamped, italic);
        } finally {
            IN_HOOK.set(prev);
        }
    }

    private static MatrixCursor notFoundFontCursor() {
        MatrixCursor cursor = new MatrixCursor(new String[] {
            FontsContract.Columns._ID,
            FontsContract.Columns.FILE_ID,
            FontsContract.Columns.TTC_INDEX,
            FontsContract.Columns.VARIATION_SETTINGS,
            FontsContract.Columns.WEIGHT,
            FontsContract.Columns.ITALIC,
            FontsContract.Columns.RESULT_CODE,
        });
        cursor.addRow(new Object[] { 0L, 0, 0, null, 400, 0, FontsContract.Columns.RESULT_CODE_FONT_NOT_FOUND });
        return cursor;
    }

    private static boolean queryArgsMatchGoogleSans(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Uri) {
                if (isGoogleSansTarget(arg.toString())) return true;
            } else if (arg instanceof String) {
                if (isGoogleSansTarget((String) arg)) return true;
            } else if (arg instanceof String[]) {
                for (String s : (String[]) arg) {
                    if (isGoogleSansTarget(s)) return true;
                }
            } else if (arg instanceof Bundle) {
                Bundle b = (Bundle) arg;
                String sel = b.getString(ContentResolver.QUERY_ARG_SQL_SELECTION);
                if (isGoogleSansTarget(sel)) return true;
                String[] selArgs = b.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);
                if (selArgs != null) {
                    for (String s : selArgs) {
                        if (isGoogleSansTarget(s)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static void hookContentResolver() {
        XposedBridge.hookAllMethods(ContentResolver.class, "query", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_HOOK.get())) return;
                if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Uri)) return;
                Uri uri = (Uri) param.args[0];
                if (!GMS_FONTS_AUTHORITY.equals(uri.getAuthority())) return;
                if (queryArgsMatchGoogleSans(param.args)) {
                    param.setResult(notFoundFontCursor());
                }
            }
        });
    }

    private static void hookFontsContract() {
        XposedBridge.hookAllMethods(FontsContract.class, "fetchFonts", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_HOOK.get())) return;
                if (param.args == null) return;
                for (Object arg : param.args) {
                    if (arg instanceof FontRequest) {
                        FontRequest req = (FontRequest) arg;
                        if (GMS_FONTS_AUTHORITY.equals(req.getProviderAuthority())
                                && isGoogleSansTarget(req.getQuery())) {
                            param.setThrowable(new PackageManager.NameNotFoundException("Google Sans redirected to system font"));
                            return;
                        }
                    }
                }
            }
        });
    }

    private static void hookResourcesGetFont() {
        XposedBridge.hookAllMethods(Resources.class, "getFont", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_HOOK.get())) return;
                if (!(param.thisObject instanceof Resources) || param.args == null || param.args.length == 0) return;
                if (!(param.args[0] instanceof Integer)) return;
                int resId = (Integer) param.args[0];
                if (resId == 0) return;
                try {
                    String entry = ((Resources) param.thisObject).getResourceEntryName(resId);
                    if (isGoogleSansTarget(entry)) {
                        param.setResult(systemSans(inferWeight(entry, Typeface.NORMAL), inferItalic(entry, Typeface.NORMAL)));
                    }
                } catch (Throwable ignored) { }
            }
        });
    }

    private static void hookTypeface() {
        XposedBridge.hookAllMethods(Typeface.class, "create", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_HOOK.get())) return;
                if (param.args == null || param.args.length < 2) return;
                if (param.args[0] instanceof String && param.args[1] instanceof Integer) {
                    String family = (String) param.args[0];
                    if (isGoogleSansTarget(family)) {
                        int style = (Integer) param.args[1];
                        param.setResult(systemSans(inferWeight(family, style), inferItalic(family, style)));
                    }
                }
            }
        });

        XposedBridge.hookAllMethods(Typeface.class, "createFromAsset", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_HOOK.get())) return;
                if (param.args == null || param.args.length < 2) return;
                if (param.args[1] instanceof String) {
                    String path = (String) param.args[1];
                    if (isGoogleSansTarget(path)) {
                        param.setResult(systemSans(inferWeight(path, Typeface.NORMAL), inferItalic(path, Typeface.NORMAL)));
                    }
                }
            }
        });

        XposedBridge.hookAllMethods(Typeface.class, "createFromFile", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_HOOK.get())) return;
                if (param.args == null || param.args.length == 0) return;
                String path = null;
                if (param.args[0] instanceof File) {
                    path = ((File) param.args[0]).getPath();
                } else if (param.args[0] instanceof String) {
                    path = (String) param.args[0];
                }
                if (path != null && (isGoogleSansTarget(path) || isGmsFontCachePath(path))) {
                    param.setResult(systemSans(inferWeight(path, Typeface.NORMAL), inferItalic(path, Typeface.NORMAL)));
                }
            }
        });

        XposedBridge.hookAllConstructors(Typeface.Builder.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_HOOK.get()) || param.thisObject == null || param.args == null) return;
                String path = null;
                if (param.args.length == 1 && param.args[0] instanceof File) {
                    path = ((File) param.args[0]).getPath();
                } else if (param.args.length == 1 && param.args[0] instanceof String) {
                    path = (String) param.args[0];
                } else if (param.args.length == 2 && param.args[0] instanceof AssetManager && param.args[1] instanceof String) {
                    path = (String) param.args[1];
                }
                if (path != null && (isGoogleSansTarget(path) || isGmsFontCachePath(path))) {
                    BUILDER_OVERRIDES.put(param.thisObject,
                        new BuilderOverride(inferWeight(path, Typeface.NORMAL), inferItalic(path, Typeface.NORMAL)));
                }
            }
        });

        XposedHelpers.findAndHookMethod(Typeface.Builder.class, "setWeight", int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                BuilderOverride ov = BUILDER_OVERRIDES.get(param.thisObject);
                if (ov != null && param.args[0] instanceof Integer) {
                    ov.weight = (Integer) param.args[0];
                }
            }
        });

        XposedHelpers.findAndHookMethod(Typeface.Builder.class, "setItalic", boolean.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                BuilderOverride ov = BUILDER_OVERRIDES.get(param.thisObject);
                if (ov != null && param.args[0] instanceof Boolean) {
                    ov.italic = (Boolean) param.args[0];
                }
            }
        });

        XposedHelpers.findAndHookMethod(Typeface.Builder.class, "build", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(IN_HOOK.get())) return;
                BuilderOverride ov = BUILDER_OVERRIDES.get(param.thisObject);
                if (ov != null) {
                    param.setResult(systemSans(ov.weight, ov.italic));
                }
            }
        });
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (load == null || load.packageName == null) return;
        if ("android".equals(load.packageName)
                || "system".equals(load.packageName)
                || "com.android.systemui".equals(load.packageName)
                || "io.github.shishui611_art.ziyu".equals(load.packageName)) {
            return;
        }
        try {
            hookContentResolver();
            hookFontsContract();
            hookResourcesGetFont();
            hookTypeface();
        } catch (Throwable error) {
            XposedBridge.log("Ziyu GoogleSans hook unavailable: " + error.getClass().getSimpleName());
        }
    }
}
