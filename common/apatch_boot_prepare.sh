#!/system/bin/sh
# Original Ziyu code, GPL-3.0-only. APatch's common hook precedes its provider
# scan AND the modules_update rename. Never invoke a provider or mount here.

_zap_value() { sed -n "s/^$2=//p" "$1" 2>/dev/null | head -n1 | tr -d '\r\n'; }
_zap_hash() (
    _zap_digest=$(sha256sum "$1" 2>/dev/null | awk '{print $1}')
    case "$_zap_digest" in ''|*[!0-9a-f]*) return 1 ;; esac
    [ "${#_zap_digest}" = 64 ] || return 1
    printf '%s\n' "$_zap_digest"
)
_zap_manifest_hash() {
    if [ -e "$1/config/font-payload-manifest.conf" ]; then
        _zap_hash "$1/config/font-payload-manifest.conf"
    else
        printf 'none\n'
    fi
}
_zap_identity() {
    case "${APATCH:-}" in true|1) ;; *) return 1 ;; esac
    [ -z "${KSU_VER_CODE:-}${MAGISK_VER_CODE:-}${SUKISU_VER_CODE:-}" ] || return 1
    [ "$(id -u)" = 0 ] && [ "$(getprop sys.boot_completed 2>/dev/null)" != 1 ] || return 1
    _zap_ns=$(readlink /proc/1/ns/mnt 2>/dev/null)
    [ -n "$_zap_ns" ] && [ "$(readlink /proc/self/ns/mnt 2>/dev/null)" = "$_zap_ns" ]
}
_zap_module_safe() {
    case "$1" in /data/adb/modules/ziyu|/data/adb/modules_update/ziyu) ;; *) return 1 ;; esac
    [ -d "$1" ] && [ ! -L "$1" ] && [ "$(readlink -f "$1")" = "$1" ] || return 1
    for _zap_child in common config logs .ziyu-state; do
        [ ! -L "$1/$_zap_child" ] || return 1
    done
    [ "$(_zap_value "$1/module.prop" id)" = ziyu ]
}
_zap_log() {
    mkdir -p "$1/logs" || return 1
    printf '[%s] [APATCH-PRE-MOUNT] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$2" >> "$1/logs/mount-backend.log"
}

ziyu_apatch_hook_install() (
    _zap_install_module="$1"
    . "$_zap_install_module/common/root_manager_detection.sh" || return 1
    luoshu_detect_root_manager >/dev/null || return 1
    [ "$ROOT_MANAGER" = APatch ] || return 0
    _zap_hook=/data/adb/post-fs-data.d/ziyu-apatch-pre-mount.sh
    [ ! -L /data/adb/post-fs-data.d ] || return 1
    [ ! -L "$_zap_hook" ] || return 1
    if [ -e "$_zap_hook" ]; then
        grep -qx '# ziyu-owned-apatch-pre-mount-v1' "$_zap_hook" || return 1
    fi
    mkdir -p /data/adb/post-fs-data.d || return 1
    (umask 077; cat > "$_zap_hook.tmp.$$" <<'ZIYU_APATCH_HOOK'
#!/system/bin/sh
# ziyu-owned-apatch-pre-mount-v1
# APatch calls this before moving modules_update and scanning provider files.
case "${APATCH:-}" in true|1) ;; *) exit 0 ;; esac
for _ziyu_boot_module in /data/adb/modules_update/ziyu /data/adb/modules/ziyu; do
    [ -d "$_ziyu_boot_module" ] || continue
    [ -f "$_ziyu_boot_module/common/apatch_boot_prepare.sh" ] || exit 0
    exec sh "$_ziyu_boot_module/common/apatch_boot_prepare.sh" prepare "$_ziyu_boot_module"
done
exit 0
ZIYU_APATCH_HOOK
    ) || return 1
    chmod 0755 "$_zap_hook.tmp.$$" && mv -f "$_zap_hook.tmp.$$" "$_zap_hook" || {
        rm -f "$_zap_hook.tmp.$$"; return 1;
    }
)

ziyu_apatch_hook_remove() {
    _zap_hook=/data/adb/post-fs-data.d/ziyu-apatch-pre-mount.sh
    [ ! -L /data/adb/post-fs-data.d ] || return 1
    [ ! -L "$_zap_hook" ] || return 1
    [ -f "$_zap_hook" ] || return 0
    grep -qx '# ziyu-owned-apatch-pre-mount-v1' "$_zap_hook" || return 0
    rm -f "$_zap_hook"
}

# Rebase only owned path fields, after the preparation child has finished.
# APatch itself moves the staged directory; we do not install or move modules.
_zap_rebase() (
    _zap_from="$1"
    [ "$_zap_from" = /data/adb/modules_update/ziyu ] || return 0
    for _zap_name in mount-backend.conf font-payload-activated.conf \
        universal-font-activated.conf font-undo.conf font-live-boot-previous.conf; do
        _zap_file="$_zap_from/config/$_zap_name"
        [ -e "$_zap_file" ] || continue
        [ -f "$_zap_file" ] && [ ! -L "$_zap_file" ] || return 1
        awk -v src="$_zap_from" -v dst=/data/adb/modules/ziyu '
            /^(retired|source|provider_content_root)=/ {
                eq=index($0,"="); value=substr($0,eq+1)
                if (value==src || index(value,src "/")==1)
                    $0=substr($0,1,eq) dst substr(value,length(src)+1)
            } {print}
        ' "$_zap_file" > "$_zap_file.tmp.$$" && chmod 0600 "$_zap_file.tmp.$$" && \
            mv -f "$_zap_file.tmp.$$" "$_zap_file" || return 1
    done
    # An interrupted publisher journal must remain recoverable after the rename.
    _zap_file="$_zap_from/.ziyu-state/provider-publish.journal"
    [ -e "$_zap_file" ] || return 0
    [ -f "$_zap_file" ] && [ ! -L "$_zap_file" ] || return 1
    awk -F '|' -v OFS='|' -v src="$_zap_from" -v dst=/data/adb/modules/ziyu '
        {for(i=1;i<=3;i++) if($i==src || index($i,src "/")==1) $i=dst substr($i,length(src)+1); print}
    ' "$_zap_file" > "$_zap_file.tmp.$$" && chmod 0600 "$_zap_file.tmp.$$" && \
        mv -f "$_zap_file.tmp.$$" "$_zap_file"
)

# A small, boot-scoped receipt prevents the later module hook from activating
# or publishing again after NoMount has pinned the early publication's inodes.
ziyu_apatch_boot_ready() (
    _zap_ready_module="$1"
    _zap_ready_boot="$2"
    _zap_identity && _zap_module_safe "$_zap_ready_module" || return 1
    _zap_receipt="$_zap_ready_module/config/apatch-pre-mount.conf"
    [ "$(_zap_value "$_zap_receipt" state)" = prepared ] && \
        [ "$(_zap_value "$_zap_receipt" boot_id)" = "$_zap_ready_boot" ] && \
        [ "$(_zap_value "$_zap_receipt" module_destination)" = /data/adb/modules/ziyu ] || return 1
    _zap_entry_sha=$(_zap_hash "$_zap_ready_module/post-fs-data.sh") || return 1
    _zap_runtime_sha=$(_zap_hash "$_zap_ready_module/common/mount_backend_runtime.sh") || return 1
    _zap_manifest_sha=$(_zap_manifest_hash "$_zap_ready_module") || return 1
    [ "$(_zap_value "$_zap_receipt" entry_sha256)" = "$_zap_entry_sha" ] && \
        [ "$(_zap_value "$_zap_receipt" runtime_sha256)" = "$_zap_runtime_sha" ] && \
        [ "$(_zap_value "$_zap_receipt" manifest_sha256)" = "$_zap_manifest_sha" ] && \
        [ "$(_zap_value "$_zap_ready_module/config/mount-backend.conf" boot_id)" = "$_zap_ready_boot" ]
)

ziyu_apatch_boot_prepare() (
    _zap_prepare_module="$1"
    if [ -f "$_zap_prepare_module/common/boot_ota_guard.sh" ]; then
        MODDIR="$_zap_prepare_module" MODULE_DIR="$_zap_prepare_module" \
            sh "$_zap_prepare_module/common/boot_ota_guard.sh" hook "$_zap_prepare_module" >> "$_zap_prepare_module/logs/boot-ota-guard.log" 2>&1
        _zap_guard_rc=$?
        case "$_zap_guard_rc" in
            0) ;;
            10|12)
                _zap_log "$_zap_prepare_module" "pre-scan stopped by boot guard rc=$_zap_guard_rc; owned disable prevents provider scan"
                return 0
                ;;
            *)
                _zap_log "$_zap_prepare_module" "boot guard failed rc=$_zap_guard_rc; refusing APatch provider preparation"
                return 1
                ;;
        esac
    fi
    _zap_identity && _zap_module_safe "$_zap_prepare_module" || return 1
    # APatch carries active disable/remove flags into the pending installation.
    # Respect them here too, before that manager-owned move has happened.
    for _zap_exclusion_module in /data/adb/modules/ziyu "$_zap_prepare_module"; do
        [ ! -e "$_zap_exclusion_module/disable" ] && [ ! -e "$_zap_exclusion_module/remove" ] || return 0
        . "$_zap_prepare_module/common/skip_mount_ownership.sh" || return 1
        ziyu_foreign_skip_mount_present "$_zap_exclusion_module" && return 0
    done
    _zap_boot=$(cat /proc/sys/kernel/random/boot_id | tr -d '\r\n')
    [ -n "$_zap_boot" ] || return 1
    ziyu_apatch_boot_ready "$_zap_prepare_module" "$_zap_boot" && return 0
    mkdir -p "$_zap_prepare_module/config" || return 1
    _zap_receipt="$_zap_prepare_module/config/apatch-pre-mount.conf"
    printf 'state=running\nboot_id=%s\n' "$_zap_boot" > "$_zap_receipt.tmp.$$" && \
        chmod 0600 "$_zap_receipt.tmp.$$" && mv -f "$_zap_receipt.tmp.$$" "$_zap_receipt" || return 1
    _zap_log "$_zap_prepare_module" "preparing before provider scan source=$_zap_prepare_module"
    ZIYU_APATCH_PRE_MOUNT=1 sh "$_zap_prepare_module/post-fs-data.sh"
    _zap_rc=$?
    _zap_rebase "$_zap_prepare_module" || _zap_rc=1
    if [ "$_zap_rc" != 0 ] || \
        [ "$(_zap_value "$_zap_prepare_module/config/mount-backend.conf" boot_id)" != "$_zap_boot" ]; then
        _zap_log "$_zap_prepare_module" "preparation failed rc=$_zap_rc; late hook retains verification/fallback"
        return 1
    fi
    _zap_entry_sha=$(_zap_hash "$_zap_prepare_module/post-fs-data.sh") || return 1
    _zap_runtime_sha=$(_zap_hash "$_zap_prepare_module/common/mount_backend_runtime.sh") || return 1
    _zap_manifest_sha=$(_zap_manifest_hash "$_zap_prepare_module") || return 1
    {
        printf 'schema=ziyu-apatch-pre-mount-v1\nstate=prepared\nboot_id=%s\n' "$_zap_boot"
        printf 'module_destination=/data/adb/modules/ziyu\n'
        printf 'entry_sha256=%s\n' "$_zap_entry_sha"
        printf 'runtime_sha256=%s\n' "$_zap_runtime_sha"
        printf 'manifest_sha256=%s\n' "$_zap_manifest_sha"
    } > "$_zap_receipt.tmp.$$" && chmod 0600 "$_zap_receipt.tmp.$$" && \
        mv -f "$_zap_receipt.tmp.$$" "$_zap_receipt" || return 1
    _zap_log "$_zap_prepare_module" 'payload ready; provider scan remains owned by APatch'
)

if [ "${0##*/}" = apatch_boot_prepare.sh ]; then
    case "${1:-}" in
        prepare) ziyu_apatch_boot_prepare "$2" ;;
        *) exit 2 ;;
    esac
fi
