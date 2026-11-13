#!/system/bin/sh
# Manager-mount backend.
#
# The module publishes its payload as an ordinary module tree and lets the Root
# manager (KernelSU / APatch / Magisk-compatible) mount it during boot. This
# module deliberately creates no bind or overlay mounts and does not depend on a
# metamodule, so the visible /system files keep ordinary mount semantics instead
# of VFS redirection artefacts.
#
# A font change is published during post-fs-data and becomes visible on the next
# boot, because the manager performs its mount stage before module scripts run.
# A publication whose payload did not change is NOT republished: rewriting the
# receipt every boot would make route verification wait for a reboot forever.
set +e

ZIYU_MANAGER_APPLY_NEW=0
ZIYU_MANAGER_VERIFY_REASON=none

_zmb_module() { printf '%s\n' "${1:-${MODDIR:-${MODULE_DIR:-/data/adb/modules/ziyu}}}"; }

_zmb_log() {
    _zmb_log_module=$(_zmb_module "${2:-}")
    mkdir -p "$_zmb_log_module/logs" 2>/dev/null || true
    printf '[%s] [MANAGER-MOUNT] %s\n' \
        "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$1" \
        >> "$_zmb_log_module/logs/mount-backend.log" 2>/dev/null || true
}

_zmb_receipt() { printf '%s/.ziyu-state/manager-published.conf\n' "$(_zmb_module "$1")"; }

_zmb_active_font() {
    head -n1 "$(_zmb_module "$1")/config/active_font.conf" 2>/dev/null | tr -d '\r\n'
}

_zmb_manifest_sha() {
    sha256sum "$(_zmb_module "$1")/config/font-payload-manifest.conf" 2>/dev/null | awk '{print $1}'
}

# A metamodule takes over module mounting from the Root manager. Manager mode
# cannot work while it is active, and silently reporting "pending" would hide it.
_zmb_metamodule_active() {
    [ -e /data/adb/metamodule ] || [ -L /data/adb/metamodule ]
}

# 0 = already published with unchanged payload, 3 = newly published, 1 = failed.
ziyu_manager_backend_apply() {
    _zmb_m=$(_zmb_module "${1:-}")
    ZIYU_MANAGER_APPLY_NEW=0
    _zmb_font=$(_zmb_active_font "$_zmb_m")
    _zmb_sha=$(_zmb_manifest_sha "$_zmb_m")
    case "$_zmb_font" in
        ''|default)
            # 强制清理：不依赖发布阶段，直接清掉已发布字体树/回执/槽位扩展，
            # 下次开机 NoMount/管理器扫描不到字域字体规则，回到原厂文件。
            if [ -f "$_zmb_m/common/restore_stock_factory.sh" ]; then
                MODDIR="$_zmb_m" MODULE_DIR="$_zmb_m" \
                    sh "$_zmb_m/common/restore_stock_factory.sh" "$_zmb_m" >/dev/null 2>&1 || return 1
            fi
            rm -f "$(_zmb_receipt "$_zmb_m")" 2>/dev/null || true
            _zmb_log 'default font: published font trees cleared, receipt retracted' "$_zmb_m"
            return 0
            ;;
    esac
    _zmb_receipt_file=$(_zmb_receipt "$_zmb_m")
    if [ -s "$_zmb_receipt_file" ]; then
        _zmb_old_font=$(sed -n 's/^font=//p' "$_zmb_receipt_file" 2>/dev/null | head -n1)
        _zmb_old_sha=$(sed -n 's/^manifest_sha256=//p' "$_zmb_receipt_file" 2>/dev/null | head -n1)
        if [ -n "$_zmb_sha" ] && [ "$_zmb_old_font" = "$_zmb_font" ] && [ "$_zmb_old_sha" = "$_zmb_sha" ]; then
            _zmb_log "payload unchanged; keep previous receipt and verification window" "$_zmb_m"
            return 0
        fi
    fi
    if ! type ziyu_provider_payload_publish >/dev/null 2>&1; then
        _zmb_log 'publish helper unavailable' "$_zmb_m"
        return 1
    fi
    if ! ziyu_provider_payload_publish "$_zmb_m" "$_zmb_m" nested-system; then
        _zmb_log "publish failed font=$_zmb_font" "$_zmb_m"
        return 1
    fi
    # The manager only mounts a module whose exclusion markers are absent. Only
    # markers this module owns are removed; a foreign skip_mount is left alone.
    if ! ziyu_skip_release_owned "$_zmb_m"; then
        _zmb_log 'owned skip marker release failed' "$_zmb_m"
        return 1
    fi
    mkdir -p "$_zmb_m/.ziyu-state" 2>/dev/null || return 1
    {
        printf 'boot_id=%s\n' "$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')"
        printf 'font=%s\n' "$_zmb_font"
        printf 'manifest_sha256=%s\n' "$_zmb_sha"
    } > "$_zmb_receipt_file" 2>/dev/null || return 1
    ZIYU_MANAGER_APPLY_NEW=1
    _zmb_log "published for manager mount font=$_zmb_font" "$_zmb_m"
    return 3
}

# 0 = routes verified, 2 = publication happened this boot (manager has not
# mounted it yet), 1 = verification failed. Failure reason is left in
# ZIYU_MANAGER_VERIFY_REASON.
ziyu_manager_backend_verify() {
    _zmb_m=$(_zmb_module "${1:-}")
    ZIYU_MANAGER_VERIFY_REASON=none
    _zmb_font=$(_zmb_active_font "$_zmb_m")
    case "$_zmb_font" in ''|default) return 0 ;; esac
    _zmb_receipt_file=$(_zmb_receipt "$_zmb_m")
    if [ ! -s "$_zmb_receipt_file" ]; then
        ZIYU_MANAGER_VERIFY_REASON=manager-publication-missing
        return 1
    fi
    _zmb_pub_boot=$(sed -n 's/^boot_id=//p' "$_zmb_receipt_file" 2>/dev/null | head -n1)
    _zmb_now_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')
    _zmb_same_boot=0
    [ -n "$_zmb_pub_boot" ] && [ "$_zmb_pub_boot" = "$_zmb_now_boot" ] && _zmb_same_boot=1
    _zmb_src="$_zmb_m/.ziyu-payload"
    [ -d "$_zmb_src/system/fonts" ] || {
        ZIYU_MANAGER_VERIFY_REASON=manager-payload-missing
        return 1
    }
    _zmb_total=0
    _zmb_ok=0
    _zmb_hashed=0
    for _zmb_file in "$_zmb_src"/system/fonts/*; do
        [ -f "$_zmb_file" ] || continue
        _zmb_name=${_zmb_file##*/}
        _zmb_visible="/system/fonts/$_zmb_name"
        _zmb_total=$((_zmb_total + 1))
        [ -f "$_zmb_visible" ] || continue
        _zmb_s1=$(wc -c < "$_zmb_file" 2>/dev/null | tr -d ' ')
        _zmb_s2=$(wc -c < "$_zmb_visible" 2>/dev/null | tr -d ' ')
        [ -n "$_zmb_s1" ] && [ "$_zmb_s1" = "$_zmb_s2" ] || continue
        if [ "$_zmb_hashed" -lt 3 ]; then
            _zmb_hashed=$((_zmb_hashed + 1))
            _zmb_h1=$(sha256sum "$_zmb_file" 2>/dev/null | awk '{print $1}')
            _zmb_h2=$(sha256sum "$_zmb_visible" 2>/dev/null | awk '{print $1}')
            [ -n "$_zmb_h1" ] && [ "$_zmb_h1" = "$_zmb_h2" ] || continue
        fi
        _zmb_ok=$((_zmb_ok + 1))
    done
    [ "$_zmb_total" -gt 0 ] || {
        ZIYU_MANAGER_VERIFY_REASON=manager-no-payload-fonts
        return 1
    }
    if [ "$_zmb_ok" -ne "$_zmb_total" ]; then
        if [ "$_zmb_same_boot" = 1 ]; then
            # 本次开机刚发布，元模块/管理器可能还没把新规则注入完；
            # 保持 pending，等 post-mount/service 下一轮再按内容核对。
            ZIYU_MANAGER_VERIFY_REASON=manager-mount-awaiting-verification
            return 2
        fi
        if _zmb_metamodule_active; then
            ZIYU_MANAGER_VERIFY_REASON=manager-mount-delegated-to-metamodule
            _zmb_log "route mismatch ok=$_zmb_ok total=$_zmb_total; metamodule owns module mounting" "$_zmb_m"
        else
            ZIYU_MANAGER_VERIFY_REASON=manager-route-mismatch
            _zmb_log "route mismatch ok=$_zmb_ok total=$_zmb_total" "$_zmb_m"
        fi
        return 1
    fi
    _zmb_log "route verified ok=$_zmb_ok total=$_zmb_total same_boot=$_zmb_same_boot" "$_zmb_m"
    return 0
}

ziyu_manager_backend_retract() {
    _zmb_m=$(_zmb_module "${1:-}")
    rm -f "$(_zmb_receipt "$_zmb_m")" 2>/dev/null || true
    _zmb_log 'receipt retracted' "$_zmb_m"
    return 0
}
