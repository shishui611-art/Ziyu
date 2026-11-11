#!/system/bin/sh
# Export ROM defaults as reusable library sources without changing mounts.
# 折中模式：只导出每个角色的 Regular 字重（约 2~3 分钟），支持后台 start/status。
set +e
MODDIR="${MODDIR:-${MODULE_DIR:-${0%/*}/..}}"
MODDIR="$(CDPATH= cd -- "$MODDIR" 2>/dev/null && pwd)" || MODDIR="${MODDIR}"
PYROOT="$MODDIR/common/python"
PYBIN="$PYROOT/bin/ziyu-python"
[ -x "$PYBIN" ] || { printf '{"status":"error","message":"系统字体导出运行时不可用"}\n'; exit 1; }
if [ -f "$MODDIR/common/user_storage.sh" ]; then
    . "$MODDIR/common/user_storage.sh"
    ziyu_user_storage_environment "$MODDIR" 2>/dev/null || true
fi
DEST="${ZIYU_PUBLIC_DIR:-/sdcard/ziyu}/fonts"
STATE="$MODDIR/config/system-font-import.state"
RESULT="$MODDIR/config/system-font-import.result.json"
LOG="$MODDIR/logs/system-font-import.log"
mkdir -p "$DEST" "$MODDIR/config" "$MODDIR/logs" 2>/dev/null || true

export_once() {
    # 已有条目直接返回。
    if [ -s "$DEST/SystemDefaultCjk.conf" ] && [ -s "$DEST/SystemDefaultLatin.conf" ] && [ -s "$DEST/SystemDefaultDigit.conf" ]; then
        printf '{"status":"ok","data":{"cached":true,"fonts":[{"id":"SystemDefaultCjk","name":"系统默认 · 中文"},{"id":"SystemDefaultLatin","name":"系统默认 · 英文"},{"id":"SystemDefaultDigit","name":"系统默认 · 数字"}]}}\n'
        return 0
    fi
    # 快速路径只在当前是系统默认字体时可用（否则 /system/fonts 不是原厂内容）。
    _eo_active=''
    IFS= read -r _eo_active < "$MODDIR/config/active_font.conf" 2>/dev/null || _eo_active=''
    if [ -z "$_eo_active" ] || [ "$_eo_active" = default ]; then
        _eo_cjk=''; _eo_latin=''; _eo_digit=''
        for _eo_f in /system/fonts/SysFont-Regular.ttf /system/fonts/SysSans-Regular.ttf /system/fonts/NotoSansCJK-Regular.ttc /system/fonts/OPPOSans-Regular.ttf; do
            [ -f "$_eo_f" ] && { _eo_cjk=$_eo_f; break; }
        done
        for _eo_f in /system/fonts/Roboto-Regular.ttf /system/fonts/GoogleSans-Regular.ttf /system/fonts/OPlusSans-Regular.ttf /system/fonts/OplusSans-Regular.ttf; do
            [ -f "$_eo_f" ] && { _eo_latin=$_eo_f; break; }
        done
        for _eo_f in /system/fonts/OSans-Solid-Digits-VF.ttf /system/fonts/AndroidClock.ttf "$_eo_latin"; do
            [ -f "$_eo_f" ] && { _eo_digit=$_eo_f; break; }
        done
        if [ -n "$_eo_cjk" ] && [ -n "$_eo_latin" ] && [ -n "$_eo_digit" ]; then
            _eo_cjk_ext=${_eo_cjk##*.}; _eo_latin_ext=${_eo_latin##*.}; _eo_digit_ext=${_eo_digit##*.}
            # 清掉上一轮可能留下的多字重旧文件，避免字体库里出现重复/过期条目。
            rm -f "$DEST"/SystemDefaultCjk-* "$DEST"/SystemDefaultLatin-* "$DEST"/SystemDefaultDigit-* 2>/dev/null
            cp -f "$_eo_cjk" "$DEST/SystemDefaultCjk-Regular.$_eo_cjk_ext" 2>/dev/null &&
            cp -f "$_eo_latin" "$DEST/SystemDefaultLatin-Regular.$_eo_latin_ext" 2>/dev/null &&
            cp -f "$_eo_digit" "$DEST/SystemDefaultDigit-Regular.$_eo_digit_ext" 2>/dev/null &&
            printf 'name=系统默认 · 中文\nsupports_cjk=true\nsource=system-default\n' > "$DEST/SystemDefaultCjk.conf" &&
            printf 'name=系统默认 · 英文\nsupports_cjk=false\nsource=system-default\n' > "$DEST/SystemDefaultLatin.conf" &&
            printf 'name=系统默认 · 数字\nsupports_cjk=false\nsource=system-default\n' > "$DEST/SystemDefaultDigit.conf" && {
                printf '{"status":"ok","data":{"fast":true,"fonts":[{"id":"SystemDefaultCjk","name":"系统默认 · 中文","files":["SystemDefaultCjk-Regular.%s"]},{"id":"SystemDefaultLatin","name":"系统默认 · 英文","files":["SystemDefaultLatin-Regular.%s"]},{"id":"SystemDefaultDigit","name":"系统默认 · 数字","files":["SystemDefaultDigit-Regular.%s"]}]}}\n' \
                    "$_eo_cjk_ext" "$_eo_latin_ext" "$_eo_digit_ext"
                return 0
            }
        fi
    fi
    PYTHONHOME="$PYROOT" \
    PYTHONPATH="$MODDIR/common:$PYROOT/lib/python3.14:$PYROOT/lib/python3.14/site-packages" \
    LD_LIBRARY_PATH="$PYROOT/lib:$PYROOT/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$PYBIN" "$MODDIR/common/system_font_library.py" --module "$MODDIR" --destination "$DEST"
}

case "${1:-export}" in
    export) export_once ;;
    start)
        if [ -f "$STATE" ] && [ "$(sed -n 's/^state=//p' "$STATE" 2>/dev/null | head -n1)" = running ]; then
            _st_pid=$(sed -n 's/^pid=//p' "$STATE" 2>/dev/null | head -n1)
            if [ -n "$_st_pid" ] && kill -0 "$_st_pid" 2>/dev/null; then
                printf '{"status":"ok","state":"running"}\n'
                exit 0
            fi
        fi
        rm -f "$RESULT" "$RESULT.tmp" "$STATE" 2>/dev/null
        (
            export_once > "$RESULT.tmp" 2>> "$LOG"
            _st_rc=$?
            if [ -s "$RESULT.tmp" ]; then mv -f "$RESULT.tmp" "$RESULT"; fi
            if [ "$_st_rc" -eq 0 ] && grep -q '"status":"ok"' "$RESULT" 2>/dev/null; then
                printf 'state=done\npid=%s\n' "$$" > "$STATE"
            else
                printf 'state=failed\npid=%s\n' "$$" > "$STATE"
            fi
        ) >/dev/null 2>&1 &
        _st_bg=$!
        printf 'state=running\npid=%s\n' "$_st_bg" > "$STATE"
        printf '{"status":"ok","state":"running"}\n'
        ;;
    status)
        _st_state=''
        [ -f "$STATE" ] && _st_state=$(sed -n 's/^state=//p' "$STATE" 2>/dev/null | head -n1)
        _st_pid=$(sed -n 's/^pid=//p' "$STATE" 2>/dev/null | head -n1)
        case "$_st_state" in
            running)
                if [ -n "$_st_pid" ] && kill -0 "$_st_pid" 2>/dev/null; then
                    printf '{"status":"ok","state":"running"}\n'
                elif [ -s "$RESULT" ] && grep -q '"status":"ok"' "$RESULT" 2>/dev/null; then
                    cat "$RESULT"
                elif [ -s "$RESULT" ]; then
                    cat "$RESULT"
                else
                    printf 'state=failed\npid=%s\n' "$_st_pid" > "$STATE" 2>/dev/null
                    printf '{"status":"error","message":"系统字体导出失败，请查看 logs/system-font-import.log"}\n'
                fi
                ;;
            done) cat "$RESULT" 2>/dev/null || printf '{"status":"error","message":"导出结果缺失"}\n' ;;
            failed) printf '{"status":"error","message":"系统字体导出失败，请查看 logs/system-font-import.log"}\n' ;;
            *) printf '{"status":"ok","state":"idle"}\n' ;;
        esac
        ;;
    *) printf '用法: system_font_library.sh <export|start|status>\n'; exit 2 ;;
esac
