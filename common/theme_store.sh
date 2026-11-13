#!/system/bin/sh
# Theme-font recovery and writes share one journal outside the removable module.
set +e
MODDIR="${MODDIR:-$(CDPATH= cd -- "${0%/*}/.." 2>/dev/null && pwd)}"
ACTION="${1:-status}"
# No backup, no work: keep normal default boots independent of Python startup.
case "$ACTION" in
    restore|restore-disable)
        if [ ! -e /data/adb/ziyu/theme-store/pending ] && \
           [ ! -e /data/adb/ziyu/theme-store/journal.json ] && \
           [ ! -s "$MODDIR/.ziyu-state/theme-backup/manifest.txt" ] && \
           [ ! -s "$MODDIR/.ziyu-state/theme-store.state" ]; then
            _ts_fast_enabled=0
            if [ -f "$MODDIR/config/tools/theme-store.conf" ]; then
                while IFS= read -r _ts_fast_line || [ -n "$_ts_fast_line" ]; do
                    [ "$_ts_fast_line" != enabled=1 ] || _ts_fast_enabled=1
                done < "$MODDIR/config/tools/theme-store.conf"
            fi
            if [ "$_ts_fast_enabled" = 1 ]; then
                printf 'enabled=0\n' > "$MODDIR/config/tools/theme-store.conf.tmp.$$" && \
                    mv -f "$MODDIR/config/tools/theme-store.conf.tmp.$$" "$MODDIR/config/tools/theme-store.conf" || exit 1
            fi
            printf '{"status":"ok","message":"没有需要还原的主题字体","data":{"restored":0,"failed":0,"enabled":false}}\n'
            exit 0
        fi
        ;;
esac
PYROOT="$MODDIR/common/python"
PYBIN="$PYROOT/bin/ziyu-python"
[ -x "$PYBIN" ] && [ -f "$MODDIR/common/theme_store.py" ] || {
    printf '{"status":"error","message":"主题字体处理组件不可用，备份已保留"}\n'
    exit 1
}
. "$MODDIR/common/slot_coverage.sh" || exit 1
ROM=$(ziyu_slot_rom "$MODDIR")
export PYTHONHOME="$PYROOT"
export PYTHONPATH="$MODDIR/common:$PYROOT/lib/python3.14:$PYROOT/lib/python3.14/site-packages"
export LD_LIBRARY_PATH="$PYROOT/lib:$PYROOT/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$PYBIN" "$MODDIR/common/theme_store.py" "$MODDIR" "$ROM" "$ACTION" "${2:-}"
