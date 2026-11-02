#!/system/bin/sh
# Root-manager action: open the installed App, installing the bundled App only if absent.
set +e
MODDIR="${0%/*}"
APK="$MODDIR/bundled/Ziyu-App.apk"
META="$MODDIR/bundled/app.prop"
HELPER="$MODDIR/common/app_installer.sh"
LOG="$MODDIR/logs/app-install.log"
mkdir -p "$MODDIR/logs" "$MODDIR/config" 2>/dev/null || true

print_line() {
    if type ui_print >/dev/null 2>&1; then ui_print "$1"; else printf '%s\n' "$1"; fi
}
APP_PACKAGE=$(sed -n 's/^package=//p' "$META" 2>/dev/null | head -n1 | tr -d '\r\n')
[ -n "$APP_PACKAGE" ] || APP_PACKAGE=io.github.shishui611_art.ziyu
case "$APP_PACKAGE" in
    io.github.shishui611_art.ziyu|io.github.shishui611_art.ziyu.debug) ;;
    *) print_line '模块 App 包名无效，请重新刷入完整模块包。'; exit 1 ;;
esac
CURRENT_USER=$(am get-current-user 2>/dev/null | head -n1 | tr -d '\r\n')
case "$CURRENT_USER" in ''|*[!0-9]*) CURRENT_USER=0 ;; esac

app_present() {
    pm path --user "$CURRENT_USER" "$APP_PACKAGE" 2>/dev/null | grep -q '^package:'
}
resolve_launcher() {
    # A launcher filter need not include CATEGORY_DEFAULT. Resolve it through the
    # package manager, then start the explicit component instead of a package-only intent.
    LAUNCH_COMPONENT=
    for _resolver in cmd pm; do
        command -v "$_resolver" >/dev/null 2>&1 || continue
        if [ "$_resolver" = cmd ]; then
            _resolved=$(cmd package resolve-activity --brief --user "$CURRENT_USER" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$APP_PACKAGE" 2>&1)
        else
            _resolved=$(pm resolve-activity --brief --user "$CURRENT_USER" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$APP_PACKAGE" 2>&1)
        fi
        _resolve_rc=$?
        printf '[ACTION-RESOLVE] resolver=%s user=%s package=%s code=%s\n%s\n' "$_resolver" "$CURRENT_USER" "$APP_PACKAGE" "$_resolve_rc" "$_resolved" >> "$LOG" 2>/dev/null || true
        [ "$_resolve_rc" -eq 0 ] || continue
        LAUNCH_COMPONENT=$(printf '%s\n' "$_resolved" | tr -d '\r' | awk -v prefix="$APP_PACKAGE/" 'index($0, prefix) == 1 { print; exit }')
        _activity=${LAUNCH_COMPONENT#*/}
        case "$_activity" in ''|*[!A-Za-z0-9_.$]*) LAUNCH_COMPONENT=; continue ;; esac
        [ -n "$LAUNCH_COMPONENT" ] && return 0
    done
    return 1
}
open_app() {
    if ! resolve_launcher; then
        print_line '未找到字域 App 的启动入口，请确认 App 未被停用；详细原因见安装日志。'
        print_line "$LOG"
        return 1
    fi
    print_line '正在打开字域 App…'
    if command -v timeout >/dev/null 2>&1; then
        _launch=$(timeout 15 am start --user "$CURRENT_USER" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n "$LAUNCH_COMPONENT" 2>&1)
    else
        _launch=$(am start --user "$CURRENT_USER" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n "$LAUNCH_COMPONENT" 2>&1)
    fi
    _launch_rc=$?
    printf '[ACTION-OPEN] user=%s component=%s code=%s\n%s\n' "$CURRENT_USER" "$LAUNCH_COMPONENT" "$_launch_rc" "$_launch" >> "$LOG" 2>/dev/null || true
    if [ "$_launch_rc" -ne 0 ] || printf '%s\n' "$_launch" | grep -Eqi 'Error:|Exception|unable to resolve|does not exist'; then
        print_line 'App 启动失败，详细原因见安装日志。'
        print_line "$LOG"
        return 1
    fi
    print_line '已打开字域 App。'
    return 0
}

# Existing App: no APK hashing, copying or reinstall on an explicit open action.
if app_present; then
    open_app
    exit $?
fi
if ! command -v pm >/dev/null 2>&1; then
    print_line '系统安装服务暂不可用，请在系统启动完成后再次点击。'
    exit 1
fi
# A package may already exist for another Android user; register it for this user first.
_existing=$(pm install-existing --user "$CURRENT_USER" "$APP_PACKAGE" 2>&1)
if app_present; then
    printf '[ACTION-REGISTER] %s\n' "$_existing" >> "$LOG" 2>/dev/null || true
    open_app
    exit $?
fi
if [ ! -s "$APK" ] || [ ! -f "$HELPER" ]; then
    print_line '未找到完整的内置 App 安装文件，请重新刷入完整模块包。'
    exit 1
fi
print_line '尚未安装字域 App，正在安装…'
_result=$(MODDIR="$MODDIR" APP_INSTALL_LOG="$LOG" sh "$HELPER" manual 2>/dev/null)
_code=$?
case "$_result" in
    installed|already-current)
        # The shared installer targets user 0; other users get their own registration.
        if ! app_present; then pm install-existing --user "$CURRENT_USER" "$APP_PACKAGE" >> "$LOG" 2>&1; fi
        if ! app_present; then
            print_line '安装尚未完成，请查看安装日志后重试。'
            exit 1
        fi
        rm -f "$MODDIR/config/app_install_manual" 2>/dev/null || true
        print_line '字域 App 已安装。'
        open_app
        exit $?
        ;;
    deferred)
        print_line '系统安装服务暂不可用，请在系统启动完成后再次点击。'
        exit 1
        ;;
    *)
        print_line 'App 安装失败，详细原因见安装日志。'
        print_line "$LOG"
        print_line "错误代码：$_code"
        exit 1
        ;;
esac
