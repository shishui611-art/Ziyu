#!/system/bin/sh
# 恢复原厂字体 / 卸载时的强制清理。
# 清掉字域发布出去的字体树与状态，保证 NoMount/管理器重新扫描后回到原厂文件，
# 不依赖“发布阶段是否被跳过”。只删字体相关内容，保留 system/bin/ziyu* 运行文件。
set +e
MODDIR="${1:-${MODDIR:-${MODULE_DIR:-/data/adb/modules/ziyu}}}"
MODDIR="$(CDPATH= cd -- "$MODDIR" 2>/dev/null && pwd)" || exit 1
LOG="$MODDIR/logs/restore-stock.log"
mkdir -p "$MODDIR/logs" 2>/dev/null || true
_log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$*" >> "$LOG" 2>/dev/null; }

_log "restore requested"

# Theme fonts are real /data files. Restore them before deleting any payload or
# rollback state; a failure keeps both recovery data and the current selection.
if [ -f "$MODDIR/common/theme_store.sh" ]; then
    MODDIR="$MODDIR" sh "$MODDIR/common/theme_store.sh" restore-disable >> "$LOG" 2>&1 || {
        _log 'theme restore incomplete; retained backups and font payload'
        printf 'status=error\nreason=theme-restore-incomplete\n'
        exit 1
    }
fi

# 1. 退出热挂载/自挂载（有脚本才执行）。
[ -f "$MODDIR/common/font_live_switch.sh" ] && \
    MODDIR="$MODDIR" MODULE_DIR="$MODDIR" sh "$MODDIR/common/font_live_switch.sh" retire >> "$LOG" 2>&1

# 2. 清空私有负载与下一启动负载。
rm -rf "$MODDIR/.ziyu-payload-next" 2>/dev/null
# 负载目录不能整个删掉：下一次切换需要从空负载克隆出新代。保留空骨架。
if [ -d "$MODDIR/.ziyu-payload" ]; then
    for _pld_entry in "$MODDIR/.ziyu-payload"/* "$MODDIR/.ziyu-payload"/.[!.]*; do
        [ -e "$_pld_entry" ] || continue
        rm -rf "$_pld_entry" 2>/dev/null
    done
fi
mkdir -p "$MODDIR/.ziyu-payload/system/fonts" 2>/dev/null
rm -rf "$MODDIR/.ziyu-retired" 2>/dev/null

# 3. 清掉所有发布分区里的 fonts 目录与字体 XML（保留 bin 等运行文件）。
_parts="system system_ext product vendor odm oem mi_ext my_bigball my_carrier my_company my_engineering my_heytap my_manifest my_preload my_product my_region my_reserve my_stock"
for _p in $_parts; do
    rm -rf "$MODDIR/$_p/fonts" "$MODDIR/$_p/system/fonts" 2>/dev/null
    rm -f "$MODDIR/$_p/etc/fonts.xml" "$MODDIR/$_p/etc/font_fallback.xml" \
          "$MODDIR/$_p/system/etc/fonts.xml" "$MODDIR/$_p/system/etc/font_fallback.xml" 2>/dev/null
done

# 3b. 删掉空的分区目录：NoMount 会把空目录当成待注入分区，
# 遇到设备没有的分区（如 mi_ext）会 abort 全部 VFS injection。
for _p in $_parts; do
    [ -d "$MODDIR/$_p" ] && [ -z "$(ls -A "$MODDIR/$_p" 2>/dev/null)" ] && rm -rf "$MODDIR/$_p" 2>/dev/null
    [ -d "$MODDIR/system/$_p" ] && [ -z "$(ls -A "$MODDIR/system/$_p" 2>/dev/null)" ] && rmdir "$MODDIR/system/$_p" 2>/dev/null
done

# 3c. 清掉负载里的空分区目录，避免下次发布又把空目录带进模块根。
if [ -d "$MODDIR/.ziyu-payload" ]; then
    for _p in $_parts; do
        [ -d "$MODDIR/.ziyu-payload/$_p" ] && [ -z "$(ls -A "$MODDIR/.ziyu-payload/$_p" 2>/dev/null)" ] && \
            rmdir "$MODDIR/.ziyu-payload/$_p" 2>/dev/null
    done
fi

# 4. 清掉发布回执/事务状态，避免下次开机复用旧发布树。
rm -rf "$MODDIR/.ziyu-state/provider-stage" "$MODDIR/.ziyu-state/provider-publish.journal" \
       "$MODDIR/.ziyu-state/provider-publish.committed" "$MODDIR/.ziyu-state/provider-published.conf" \
       "$MODDIR/.ziyu-state/provider-published.next" "$MODDIR/.ziyu-state/manager-published.conf" 2>/dev/null
for _bak in "$MODDIR"/.ziyu-state/provider-backup-*; do rm -rf "$_bak" 2>/dev/null; done
rm -f "$MODDIR/config/font-payload-next.conf" "$MODDIR/config/universal-font-next.conf" \
      "$MODDIR/config/font-live-transaction.conf" "$MODDIR/config/temporary-root-soft-reboot.conf" 2>/dev/null

# 5. 标记为系统默认。
mkdir -p "$MODDIR/config" 2>/dev/null
printf 'default\n' > "$MODDIR/config/active_font.conf" 2>/dev/null || true

_log "restore done: published font trees and receipts cleared"
printf 'status=ok\n'
