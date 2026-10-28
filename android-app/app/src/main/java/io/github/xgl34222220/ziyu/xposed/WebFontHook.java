package io.github.xgl34222220.ziyu.xposed;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Only executes in applications explicitly selected by the user in LSPosed. */
public final class WebFontHook implements IXposedHookLoadPackage {
    private static final Set<Method> HOOKED = ConcurrentHashMap.newKeySet();

    // Work in short batches, leave icon/private-use glyphs and code blocks alone,
    // and let the page keep its existing JavaScript and network security policy.
    private static final String SCRIPT =
        "(function(){try{if(window.__ziyuWebFont)return;window.__ziyuWebFont=1;" +
        "var q=[],scheduled=false,overflow=false,skip=/icon|glyph|symbol|emoji|material-icons|fontawesome|(^|[ _-])fa[rsbl]?(?=$|[ _-])|(^|[ _-])(bi|mdi)(?=$|[ _-])/i;" +
        "function eligible(e){for(var n=e;n&&n.nodeType===1;n=n.parentElement){" +
        "var t=n.tagName;if(/^(SCRIPT|STYLE|SVG|CANVAS|CODE|PRE|I)$/.test(t))return false;" +
        "if(n.getAttribute('aria-hidden')==='true'||n.getAttribute('role')==='img'||" +
        "skip.test(typeof n.className==='string'?n.className:'')||n.hasAttribute('data-icon'))return false;}return true;}" +
        "function mark(n){if(!n||n.nodeType!==3||/[\\ue000-\\uf8ff]/.test(n.nodeValue||'')||!/[A-Za-z0-9\\u00c0-\\u02af\\u3400-\\u9fff\\u3040-\\u30ff\\uac00-\\ud7af]/.test(n.nodeValue||''))return;" +
        "var e=n.parentElement;if(e&&!/[\\ue000-\\uf8ff]/.test(e.textContent||'')&&eligible(e)&&e.getAttribute('data-ziyu-webfont')!=='1'){" +
        "e.style.setProperty('font-family','sans-serif','important');e.setAttribute('data-ziyu-webfont','1');}}" +
        "function drain(){scheduled=false;var budget=400;while(q.length&&budget-->0){var x=q[0],n=x.nextNode();" +
        "if(n)mark(n);else q.shift();}if(!q.length&&overflow){overflow=false;q.push(document.createTreeWalker(document.documentElement,NodeFilter.SHOW_TEXT));}if(q.length)schedule();}" +
        "function schedule(){if(!scheduled){scheduled=true;(window.requestAnimationFrame||function(f){setTimeout(f,16)})(drain);}}" +
        "function add(n){if(!n)return;if(n.nodeType===3){mark(n);return;}" +
        "if(n.nodeType!==1)return;if(q.length>=256){overflow=true;return;}q.push(document.createTreeWalker(n,NodeFilter.SHOW_TEXT));schedule();}" +
        "add(document.documentElement);new MutationObserver(function(ms){ms.forEach(function(m){" +
        "if(m.type==='characterData')mark(m.target);else m.addedNodes.forEach(add);});})" +
        ".observe(document.documentElement,{childList:true,subtree:true,characterData:true});}catch(e){}})();";

    private static void watch(Class<?> clientClass) {
        try {
            Method method = clientClass.getMethod("onPageFinished", WebView.class, String.class);
            if (!HOOKED.add(method)) return;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof WebView)) return;
                    WebView view = (WebView) param.args[0];
                    try {
                        view.post(() -> {
                            try {
                                if (view.getSettings().getJavaScriptEnabled()) view.evaluateJavascript(SCRIPT, null);
                            } catch (Throwable ignored) { }
                        });
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (load == null || "android".equals(load.packageName)
                || "io.github.shishui611_art.ziyu".equals(load.packageName)) return;
        try {
            watch(WebViewClient.class);
            XposedHelpers.findAndHookMethod(WebView.class, "setWebViewClient", WebViewClient.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof WebViewClient)
                            watch(param.args[0].getClass());
                    }
                });
        } catch (Throwable error) {
            XposedBridge.log("Ziyu WebView font hook unavailable: " + error.getClass().getSimpleName());
        }
    }
}
