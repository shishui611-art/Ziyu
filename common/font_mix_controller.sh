#!/system/bin/sh
# App composite entry: delegate before loading any runtime or scanning fonts.
set +e
MODDIR="${MODDIR:-}"
if [ -z "$MODDIR" ]; then
    if [ -f "${0%/*}/../module.prop" ]; then
        MODDIR="$(CDPATH= cd -- "${0%/*}/.." 2>/dev/null && pwd)"
    else
        MODDIR=/data/adb/modules/ziyu
    fi
fi
export MODDIR
LEGACY_V14_MIX="$MODDIR/common/legacy_v14_4/mix_router.sh"
case "${1:-config}" in
    start)
        if [ -f "$MODDIR/common/boot_ota_guard.sh" ]; then
            MODDIR="$MODDIR" MODULE_DIR="$MODDIR" \
                sh "$MODDIR/common/boot_ota_guard.sh" allow-apply "$MODDIR" mix >/dev/null 2>&1
            _fmc_guard_rc=$?
            if [ "$_fmc_guard_rc" = 21 ] && [ -f "$MODDIR/common/boot_ota_recover.sh" ]; then
                . "$MODDIR/common/boot_ota_recover.sh"
                if ziyu_boot_ota_refresh "$MODDIR" 240 >>"$MODDIR/logs/mix.log" 2>&1; then
                    MODDIR="$MODDIR" MODULE_DIR="$MODDIR" \
                        sh "$MODDIR/common/boot_ota_guard.sh" allow-apply "$MODDIR" mix >/dev/null 2>&1
                    _fmc_guard_rc=$?
                fi
            fi
            if [ "$_fmc_guard_rc" -ne 0 ]; then
                printf '{"status":"error","message":"开机保护暂未解除，请在开机保护页面核对原因并完成原厂字体扫描"}\n'
                exit 1
            fi
        fi
        if [ -f "$LEGACY_V14_MIX" ]; then
            exec sh "$LEGACY_V14_MIX" "$@"
        fi
        printf '{"status":"error","message":"缺少字体组合核心，请重新安装当前版本模块"}\n'
        exit 1
        ;;
    config|status|recover|reconcile)
        if [ -f "$LEGACY_V14_MIX" ]; then
            exec sh "$LEGACY_V14_MIX" "$@"
        fi
        printf '{"status":"error","message":"缺少字体组合核心，请重新安装当前版本模块"}\n'
        exit 1
        ;;
    *) printf '{"status":"error","message":"未知组合命令"}\n'; exit 2 ;;
esac
