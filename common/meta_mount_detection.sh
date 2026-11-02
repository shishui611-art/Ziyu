#!/system/bin/sh
# Detect external font mount engines without modifying their settings.
set +e

_ziyu_meta_root() {
    printf '%s\n' "${ZIYU_META_DETECT_ROOT:-/data/adb}"
}

_ziyu_meta_prop() {
    sed -n "s/^$2=//p" "$1/module.prop" 2>/dev/null | head -n1 | tr -d '\r\n'
}

_ziyu_meta_truthy() {
    case "$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')" in
        1|true|yes|on|enabled) return 0 ;;
        *) return 1 ;;
    esac
}

_ziyu_meta_enabled() {
    [ -f "$1/module.prop" ] && [ ! -e "$1/disable" ] && [ ! -e "$1/remove" ]
}

_ziyu_meta_type_for_dir() {
    _lmtd_dir="$1"
    _lmtd_id=$(_ziyu_meta_prop "$_lmtd_dir" id | tr '[:upper:]' '[:lower:]')
    _lmtd_name=$(_ziyu_meta_prop "$_lmtd_dir" name | tr '[:upper:]' '[:lower:]')
    _lmtd_desc=$(_ziyu_meta_prop "$_lmtd_dir" description | tr '[:upper:]' '[:lower:]')
    case "$_lmtd_id $_lmtd_name $_lmtd_desc" in
        *meta-overlayfs*|*meta-overlay*) printf 'meta-overlayfs\n' ;;
        *mountify*) printf 'mountify\n' ;;
        *hybrid*mount*) printf 'hybrid-mount\n' ;;
        *nomount*) printf 'nomount\n' ;;
        *magic*mount*|*meta-mm*|*magic_mount_rs*) printf 'magic-mount\n' ;;
        *) return 1 ;;
    esac
}

# Locate a NoMount kernel CLI without assuming one packaging layout. NoMount
# Suite keeps its kernel-facing nm under bin/<android-abi>/nm; the separate
# "nomount" manager binary is not the preferred driver probe.
_ziyu_nomount_cli_for_dir() {
    _lnc_dir="${1:-}"
    [ -d "$_lnc_dir" ] || return 1
    _lnc_abi="${ZIYU_NOMOUNT_TEST_ABI:-}"
    if [ -z "$_lnc_abi" ]; then
        _lnc_abi=$(getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r\n')
        [ -n "$_lnc_abi" ] || _lnc_abi=$(getprop ro.product.cpu.abilist 2>/dev/null | cut -d, -f1 | tr -d '\r\n')
    fi
    case "$_lnc_abi" in ''|*[!A-Za-z0-9._-]*) _lnc_abi='' ;; esac
    if [ -n "$_lnc_abi" ]; then
        for _lnc_path in "$_lnc_dir/bin/$_lnc_abi/nm"; do
            [ -x "$_lnc_path" ] || continue
            printf '%s\n' "$_lnc_path"
            return 0
        done
    fi
    for _lnc_path in "$_lnc_dir/bin/nm" "$_lnc_dir/nm" "$_lnc_dir/nomount"; do
        [ -x "$_lnc_path" ] || continue
        printf '%s\n' "$_lnc_path"
        return 0
    done
    return 1
}

_ziyu_meta_active_dir() {
    if [ -n "${ZIYU_META_TEST_ACTIVE_DIR:-}" ]; then
        [ -f "$ZIYU_META_TEST_ACTIVE_DIR/module.prop" ] || return 1
        printf '%s\n' "$ZIYU_META_TEST_ACTIVE_DIR"
        return 0
    fi
    _lmad_meta="$(_ziyu_meta_root)/metamodule"
    [ -e "$_lmad_meta" ] || [ -L "$_lmad_meta" ] || return 1
    _lmad_target=$(readlink -f "$_lmad_meta" 2>/dev/null)
    [ -n "$_lmad_target" ] || _lmad_target="$_lmad_meta"
    [ -f "$_lmad_target/module.prop" ] || return 1
    printf '%s\n' "$_lmad_target"
}

_ziyu_meta_find_installed() {
    _lmfi_root="$(_ziyu_meta_root)/modules"
    _lmfi_type="$1"
    _lmfi_require_enabled="${2:-0}"
    if [ "${_lmd_installed_snapshot_ready:-0}" = 1 ]; then
        while IFS='|' read -r _lmfi_detected _lmfi_dir; do
            [ "$_lmfi_detected" = "$_lmfi_type" ] || continue
            [ "$(_ziyu_meta_type_for_dir "$_lmfi_dir")" = "$_lmfi_type" ] || continue
            if [ "$_lmfi_require_enabled" = 1 ]; then _ziyu_meta_enabled "$_lmfi_dir" || continue; fi
            printf '%s\n' "$_lmfi_dir"
            return 0
        done <<EOF_META_INSTALLED
$_lmd_installed_snapshot
EOF_META_INSTALLED
        return 1
    fi
    for _lmfi_dir in "$_lmfi_root"/*; do
        [ -d "$_lmfi_dir" ] || continue
        _lmfi_detected=$(_ziyu_meta_type_for_dir "$_lmfi_dir") || continue
        [ "$_lmfi_detected" = "$_lmfi_type" ] || continue
        if [ "$_lmfi_require_enabled" = 1 ]; then
            _ziyu_meta_enabled "$_lmfi_dir" || continue
        fi
        printf '%s\n' "$_lmfi_dir"
        return 0
    done
    return 1
}

_ziyu_meta_installed_snapshot() {
    _lmis_modules="$(_ziyu_meta_root)/modules"
    for _lmis_dir in "$_lmis_modules"/*; do
        [ -d "$_lmis_dir" ] || continue
        case "$_lmis_dir" in *'|'*) continue ;; esac
        _lmis_type=$(_ziyu_meta_type_for_dir "$_lmis_dir") || continue
        printf '%s|%s\n' "$_lmis_type" "$_lmis_dir"
    done
}

_ziyu_meta_is_owned_skip() {
    _lmis_root="${MODDIR:-${MODULE_DIR:-/data/adb/modules/ziyu}}"
    case "${1:-skip_mount}" in
        skip_mount)
            [ -f "$_lmis_root/.ziyu_skip_mount_owned" ] || [ -f "$_lmis_root/config/self-mount-owned" ]
            ;;
        skip_mountify)
            [ -f "$_lmis_root/.ziyu_skip_mountify_owned" ] || [ -f "$_lmis_root/config/self-mount-owned" ]
            ;;
        *) return 1 ;;
    esac
}

_ziyu_meta_mountify_selected() {
    _lmms_root="$(_ziyu_meta_root)"
    _lmms_config=''
    _lmms_list=''
    for _lmms_candidate in \
        "$_lmms_root/mountify/config.sh" \
        "$_lmms_root/modules/mountify/config.sh" \
        "$_lmms_root/modules/Mountify/config.sh"; do
        [ -f "$_lmms_candidate" ] && { _lmms_config="$_lmms_candidate"; break; }
    done
    for _lmms_candidate in \
        "$_lmms_root/mountify/modules.txt" \
        "$_lmms_root/modules/mountify/modules.txt" \
        "$_lmms_root/modules/Mountify/modules.txt"; do
        [ -f "$_lmms_candidate" ] && { _lmms_list="$_lmms_candidate"; break; }
    done
    _lmms_mode=2
    if [ -n "$_lmms_config" ]; then
        _lmms_mode=$(sed -n 's/^[[:space:]]*mountify_mounts[[:space:]]*=[[:space:]]*["\047]*\([0-9][0-9]*\).*/\1/p' "$_lmms_config" 2>/dev/null | tail -n1)
        [ -n "$_lmms_mode" ] || _lmms_mode=2
    fi
    case "$_lmms_mode" in
        0) return 1 ;;
        1)
            [ -n "$_lmms_list" ] || return 1
            grep -Fxq 'ziyu' "$_lmms_list" 2>/dev/null || return 1
            ;;
        2) ;;
        *) return 1 ;;
    esac
    _lmms_module="${MODDIR:-${MODULE_DIR:-/data/adb/modules/ziyu}}"
    if [ -e "$_lmms_module/skip_mountify" ] && ! _ziyu_meta_is_owned_skip skip_mountify; then
        return 1
    fi
    if [ -e "$_lmms_module/skip_mount" ] && ! _ziyu_meta_is_owned_skip skip_mount; then
        return 1
    fi
    return 0
}

_ziyu_meta_hybrid_selected() {
    _lmhs_root="$(_ziyu_meta_root)"
    _lmhs_file=''
    META_HYBRID_CONFIG_PATH=''
    META_HYBRID_DEFAULT_MODE=''
    META_HYBRID_ROUTE_REASON=hybrid-config-missing
    for _lmhs_candidate in \
        "$_lmhs_root/hybrid-mount/config.toml" \
        "${META_MODULE_DIR:-$_lmhs_root/modules/hybrid_mount}/config.toml" \
        "$_lmhs_root/modules/meta-hybrid_mount/config.toml" \
        "$_lmhs_root/modules/hybrid_mount/config.toml" \
        "$_lmhs_root/modules/hybrid-mount/config.toml"; do
        [ -f "$_lmhs_candidate" ] && { _lmhs_file="$_lmhs_candidate"; break; }
    done
    [ -n "$_lmhs_file" ] || return 1
    META_HYBRID_CONFIG_PATH="$_lmhs_file"
    [ -r "$_lmhs_file" ] || { META_HYBRID_ROUTE_REASON=hybrid-config-unreadable; return 1; }
    _lmhs_id=$(_ziyu_meta_prop "${MODDIR:-${MODULE_DIR:-/data/adb/modules/ziyu}}" id)
    [ -n "$_lmhs_id" ] || _lmhs_id=ziyu
    # Upstream Config defaults to Overlay, uses case-sensitive module IDs, and
    # accepts module/path rules. Only prove the ordinary table form here: inline
    # or dotted rule syntax must not hide a Magic/Overlay override. This is a
    # read-only conservative route check, not a replacement TOML implementation.
    _lmhs_route=$(awk -v wanted="$_lmhs_id" '
        function trim(s) { sub(/^[[:space:]]+/, "", s); sub(/[[:space:]]+$/, "", s); return s }
        function uncomment(s, i, q, c, out, escaped) {
            for (i=1; i<=length(s); i++) {
                c=substr(s,i,1)
                if (!q && c=="#") break
                out=out c
                if (escaped) { escaped=0; continue }
                if (q=="\"" && c=="\\") { escaped=1; continue }
                if (q && c==q) q=""
                else if (!q && (c=="\"" || c=="\047")) q=c
            }
            return trim(out)
        }
        function mode(s) {
            if (s !~ /^"(overlay|magic|vfs|ignore)"$/ && s !~ /^\047(overlay|magic|vfs|ignore)\047$/) { invalid=1; return "" }
            return substr(s,2,length(s)-2)
        }
        BEGIN { global="overlay"; section="" }
        {
            line=uncomment($0)
            if (line=="") next
            if (line ~ /^\[/) {
                section=line; gsub(/["\047[:space:]]/, "", section)
                if (section !~ /^\[[^][]+\]$/) invalid=1
                in_module=(section=="[rules." wanted "]")
                in_paths=(section=="[rules." wanted ".paths]")
                next
            }
            if (line !~ /=/) { if (in_module || in_paths) invalid=1; next }
            key=line; sub(/=.*/, "", key); key=trim(key)
            value=line; sub(/^[^=]*=/, "", value); value=trim(value)
            if (key=="default_mode" && (section=="" || in_module)) {
                value=mode(value)
                if (in_module) { if (module_seen++) invalid=1; module=value }
                else { if (global_seen++) invalid=1; global=value; if (global=="ignore") invalid=1 }
            } else if (in_paths) {
                value=mode(value)
                if (value!="vfs" && value!="ignore") unsafe_path=1
            } else if (in_module || section=="[rules]" || (section=="" && key ~ /^rules[.[:space:]]/)) {
                invalid=1
            }
        }
        END {
            value=(module!="" ? module : global)
            if (invalid) reason="hybrid-config-unverified"
            else if (value=="ignore") reason="hybrid-module-excluded"
            else if (value!="vfs" || unsafe_path) reason="hybrid-route-not-pure-vfs"
            else reason="hybrid-pure-vfs-scoped-unload"
            print reason "|" value
        }
    ' "$_lmhs_file" 2>/dev/null) || { META_HYBRID_ROUTE_REASON=hybrid-config-unverified; return 1; }
    # mksh treats an unquoted | in parameter-removal patterns differently from
    # host Bash. Parse fields with read, as root-manager detection does.
    IFS='|' read -r META_HYBRID_ROUTE_REASON META_HYBRID_DEFAULT_MODE <<EOF_HYBRID_ROUTE
$_lmhs_route
EOF_HYBRID_ROUTE
    [ "$META_HYBRID_ROUTE_REASON" = hybrid-pure-vfs-scoped-unload ] || return 1

    # Hybrid merges persistent and bundled blacklists. Missing files are normal;
    # an unreadable or unrecognized blacklist must never silently allow Ziyu.
    for _lmhs_blacklist in \
        "$_lmhs_root/hybrid-mount/module_blacklist.toml" \
        "${META_MODULE_DIR:-$_lmhs_root/modules/hybrid_mount}/module_blacklist.toml"; do
        [ -e "$_lmhs_blacklist" ] || [ -L "$_lmhs_blacklist" ] || continue
        [ -r "$_lmhs_blacklist" ] || { META_HYBRID_ROUTE_REASON=hybrid-config-unreadable; return 1; }
        _lmhs_blacklist_result=$(awk -v wanted="$_lmhs_id" '
            { sub(/#.*/, ""); text=text " " $0 }
            END {
                if (text ~ /^[[:space:]]*$/) { print "ok"; exit }
                if (text !~ /^[[:space:]]*blacklist[[:space:]]*=[[:space:]]*\[/) { print "unverified"; exit }
                sub(/^[^[]*\[/, "", text)
                if (text !~ /\][[:space:]]*$/) { print "unverified"; exit }
                sub(/\][[:space:]]*$/, "", text)
                while (match(text, /"[A-Za-z][A-Za-z0-9._-]*"|\047[A-Za-z][A-Za-z0-9._-]*\047/)) {
                    id=substr(text,RSTART+1,RLENGTH-2)
                    if (id==wanted) excluded=1
                    text=substr(text,1,RSTART-1) substr(text,RSTART+RLENGTH)
                }
                if (text !~ /^[[:space:],]*$/) print "unverified"
                else print (excluded ? "excluded" : "ok")
            }
        ' "$_lmhs_blacklist" 2>/dev/null) || _lmhs_blacklist_result=unverified
        case "$_lmhs_blacklist_result" in
            ok) ;;
            excluded) META_HYBRID_ROUTE_REASON=hybrid-module-excluded; return 1 ;;
            *) META_HYBRID_ROUTE_REASON=hybrid-config-unverified; return 1 ;;
        esac
    done
    return 0
}

_ziyu_meta_hybrid_route_state() {
    # Same conservative grammar as _ziyu_meta_hybrid_selected, but reports why
    # a route is not pure VFS so the ensure helper can distinguish an upstream
    # default from an explicit per-module user rule.
    _lmrs_file="$2"
    awk -v wanted="$1" '
        function trim(s) { sub(/^[[:space:]]+/, "", s); sub(/[[:space:]]+$/, "", s); return s }
        function uncomment(s, i, q, c, out, escaped) {
            for (i=1; i<=length(s); i++) {
                c=substr(s,i,1)
                if (!q && c=="#") break
                out=out c
                if (escaped) { escaped=0; continue }
                if (q=="\"" && c=="\\") { escaped=1; continue }
                if (q && c==q) q=""
                else if (!q && (c=="\"" || c=="\047")) q=c
            }
            return trim(out)
        }
        function mode(s) {
            if (s !~ /^"(overlay|magic|vfs|ignore)"$/ && s !~ /^\047(overlay|magic|vfs|ignore)\047$/) { invalid=1; return "" }
            return substr(s,2,length(s)-2)
        }
        BEGIN { global="overlay"; section=""; module_header=0; paths_header=0 }
        {
            line=uncomment($0)
            if (line=="") next
            if (line ~ /^\[/) {
                section=line; gsub(/["\047[:space:]]/, "", section)
                if (section !~ /^\[[^][]+\]$/) invalid=1
                in_module=(section=="[rules." wanted "]")
                in_paths=(section=="[rules." wanted ".paths]")
                if (in_module) module_header=1
                if (in_paths) paths_header=1
                next
            }
            if (line !~ /=/) { if (in_module || in_paths) invalid=1; next }
            key=line; sub(/=.*/, "", key); key=trim(key)
            value=line; sub(/^[^=]*=/, "", value); value=trim(value)
            if (key=="default_mode" && (section=="" || in_module)) {
                value=mode(value)
                if (in_module) { if (module_seen++) invalid=1; module=value }
                else { if (global_seen++) invalid=1; global=value; if (global=="ignore") invalid=1 }
            } else if (in_paths) {
                value=mode(value)
                if (value!="vfs" && value!="ignore") paths_unsafe=1
            } else if (in_module || section=="[rules]" || (section=="" && key ~ /^rules[.[:space:]]/)) {
                invalid=1
            }
        }
        END {
            if (invalid) { print "invalid|" global "|" }
            else if (paths_unsafe) { print "module-paths-unsafe|" global "|" }
            else if (module != "") { print "module-" module "|" global "|" module }
            else if (module_header) { print "module-section-empty|" global "|" }
            else if (paths_header) { print "paths-only-safe|" global "|" }
            else { print "no-module|" global "|" }
        }
    ' "$_lmrs_file" 2>/dev/null
}

ziyu_meta_hybrid_ensure_vfs_rule() {
    # Users must not need to edit Hybrid Mount TOML by hand: when they pick the
    # Meta backend and the only blocker is the upstream Overlay default, add a
    # Ziyu-scoped VFS rule ourselves. Explicit per-module user rules win and
    # are never overwritten; the write is backed up and verified by readback.
    ZIYU_META_ENSURE_RESULT=error
    ZIYU_META_ENSURE_DETAIL=''
    [ "${META_ENGINE:-none}" = hybrid-mount ] && [ -n "${META_MODULE_DIR:-}" ] || {
        ZIYU_META_ENSURE_RESULT=not-hybrid; return 1
    }
    [ "${META_HYBRID_ROUTE_REASON:-}" = hybrid-route-not-pure-vfs ] || {
        ZIYU_META_ENSURE_RESULT=not-needed
        ZIYU_META_ENSURE_DETAIL="${META_HYBRID_ROUTE_REASON:-unknown}"
        return 1
    }
    [ -n "${META_HYBRID_CONFIG_PATH:-}" ] || {
        ZIYU_META_ENSURE_RESULT=not-needed
        ZIYU_META_ENSURE_DETAIL=hybrid-config-missing
        return 1
    }
    _lmev_file="$META_HYBRID_CONFIG_PATH"
    [ -r "$_lmev_file" ] || { ZIYU_META_ENSURE_RESULT=config-unreadable; return 1; }
    _lmev_id=$(_ziyu_meta_prop "${MODDIR:-${MODULE_DIR:-/data/adb/modules/ziyu}}" id)
    [ -n "$_lmev_id" ] || _lmev_id=ziyu
    _lmev_state=$(_ziyu_meta_hybrid_route_state "$_lmev_id" "$_lmev_file")
    IFS='|' read -r _lmev_kind _lmev_global _lmev_module <<EOF_LMEV
$_lmev_state
EOF_LMEV
    case "$_lmev_kind" in
        invalid|'')
            ZIYU_META_ENSURE_RESULT=config-unverified; return 1 ;;
        module-paths-unsafe|module-overlay|module-magic|module-ignore|module-section-empty|paths-only-safe)
            ZIYU_META_ENSURE_RESULT=explicit-rule-wins
            ZIYU_META_ENSURE_DETAIL="$_lmev_kind"
            return 2 ;;
        module-vfs)
            ZIYU_META_ENSURE_RESULT=not-needed; return 1 ;;
        no-module) ;;
        *)
            ZIYU_META_ENSURE_RESULT=config-unverified; return 1 ;;
    esac
    _lmev_backup="$_lmev_file.ziyu-backup"
    _lmev_tmp="$_lmev_backup.tmp.$$"
    cp "$_lmev_file" "$_lmev_tmp" 2>/dev/null || { ZIYU_META_ENSURE_RESULT=backup-write-failed; return 1; }
    mv -f "$_lmev_tmp" "$_lmev_backup" 2>/dev/null || {
        rm -f "$_lmev_tmp"
        ZIYU_META_ENSURE_RESULT=backup-write-failed; return 1
    }
    {
        printf '\n# >>> ziyu-vfs-rule (added by the Ziyu font module for scoped unload)\n'
        printf '[rules.%s]\ndefault_mode = "vfs"\n' "$_lmev_id"
        printf '# <<< ziyu-vfs-rule\n'
    } >> "$_lmev_file" 2>/dev/null || {
        cat "$_lmev_backup" > "$_lmev_file" 2>/dev/null
        ZIYU_META_ENSURE_RESULT=rule-write-failed; return 1
    }
    if _ziyu_meta_hybrid_selected; then
        ZIYU_META_ENSURE_RESULT=applied
        ZIYU_META_ENSURE_DETAIL="$_lmev_backup"
        return 0
    fi
    cat "$_lmev_backup" > "$_lmev_file" 2>/dev/null
    ZIYU_META_ENSURE_RESULT=verify-failed
    return 1
}

_ziyu_meta_active_identity() {
    [ "${ZIYU_META_TEST_ASSUME_ACTIVE:-0}" = 1 ] && return 0
    _lmai_dir="$1"
    _lmai_root="$(_ziyu_meta_root)"
    _lmai_link="$_lmai_root/metamodule"
    [ -L "$_lmai_link" ] || return 1
    _lmai_resolved=$(readlink -f "$_lmai_link" 2>/dev/null)
    [ -n "$_lmai_resolved" ] || return 1
    [ "$_lmai_resolved" = "$(readlink -f "$_lmai_dir" 2>/dev/null)" ]
}

_ziyu_meta_overlay_content_base() {
    # KernelSU may expose the provider's image at MODULE_CONTENT_DIR instead
    # of <metamodule>/mnt. Only accept an existing image mount, never mkdir it.
    for _lmoc_path in "${MODULE_CONTENT_DIR:-}" "${META_ACTIVE_DIR:-${META_MODULE_DIR:-}}/mnt"; do
        case "$_lmoc_path" in /data/adb/*) ;; *) continue ;; esac
        case "$_lmoc_path" in */../*|*/..|*'|'*|*' '*|*'\\'*) continue ;; esac
        _lmoc_image="$_lmoc_path"
        case "$_lmoc_image" in */ziyu|*/Ziyu) _lmoc_image="${_lmoc_image%/*}" ;; esac
        if [ -d "$_lmoc_path" ] && type ziyu_mountpoint_ready >/dev/null 2>&1 && ziyu_mountpoint_ready "$_lmoc_image"; then
            printf '%s\n' "$_lmoc_path"
            return 0
        fi
    done
    return 1
}

_ziyu_meta_hybrid_runtime_api() {
    # Older Hybrid releases have a binary and VFS config but no scoped runtime
    # API. Its read-only status returns supported=false before the boot ledger
    # is ready, which still proves the API exists; active success is committed
    # only after PID 1 font closure and strict runtime unload readback.
    command -v timeout >/dev/null 2>&1 || return 1
    _lmhr_bin="$1/hybrid-mount"
    [ -x "$_lmhr_bin" ] || _lmhr_bin="$1/hybrid_mount"
    _lmhr_status=$(timeout 3 "$_lmhr_bin" runtime status 2>/dev/null) || return 1
    printf '%s\n' "$_lmhr_status" | grep -q '"supported"[[:space:]]*:[[:space:]]*\(true\|false\)' || return 1
    printf '%s\n' "$_lmhr_status" | grep -q '"modules"[[:space:]]*:[[:space:]]*\['
}

ziyu_nomount_kernel_probe() {
    # Read-only kernel probe: classic NoMount uses "nm version", while
    # NoMount Suite's netlink-facing nm responds to "nm v". Never use the
    # separate Suite manager's "nomount version" as proof of kernel readiness.
    NOMOUNT_KERNEL_USABLE=0
    META_NOMOUNT_VERSION=''
    [ -x "$1" ] || return 1
    for _lmd_nm_verb in version v; do
        if command -v timeout >/dev/null 2>&1; then
            META_NOMOUNT_VERSION=$(timeout 2 "$1" "$_lmd_nm_verb" 2>/dev/null)
        else
            META_NOMOUNT_VERSION=$("$1" "$_lmd_nm_verb" 2>/dev/null)
        fi
        _lmd_nm_probe_rc=$?
        if [ "$_lmd_nm_probe_rc" -eq 0 ] && [ -n "$META_NOMOUNT_VERSION" ]; then
            NOMOUNT_KERNEL_USABLE=1
            return 0
        fi
    done
    META_NOMOUNT_VERSION=''
    return 1
}

ziyu_meta_mount_detect() {
    _lmd_installed_snapshot_ready=0
    _lmd_installed_snapshot=''
    META_ENGINE=none
    META_INSTALLED=0
    META_ENABLED=0
    META_USABLE=0
    # READY means this engine can include our payload during boot; USABLE also
    # requires a supported module-scoped cleanup before a self fallback.
    META_READY=0
    META_AVAILABLE=0
    META_MODULE_DIR=''
    META_ACTIVE_DIR=''
    META_USABLE_REASON='not-installed'
    META_CLEANUP_CAPABILITY='none'
    NOMOUNT_KERNEL_USABLE=unknown
    META_NOMOUNT_CLI=''
    META_NOMOUNT_VERSION=''
    META_DETECTION_SOURCE=filesystem
    META_HYBRID_CONFIG_PATH=''
    META_HYBRID_DEFAULT_MODE=''
    META_HYBRID_ROUTE_REASON=''
    META_DETECTION_CHECKS=''

    if [ -n "${ZIYU_META_TEST_ENGINE:-}" ]; then
        META_ENGINE="$ZIYU_META_TEST_ENGINE"
        META_INSTALLED=${ZIYU_META_TEST_INSTALLED:-1}
        META_ENABLED=${ZIYU_META_TEST_ENABLED:-1}
        META_USABLE=${ZIYU_META_TEST_USABLE:-1}
        META_READY=${ZIYU_META_TEST_READY:-$META_USABLE}
        META_AVAILABLE=$META_INSTALLED
        META_DETECTION_SOURCE=test
        [ "$META_USABLE" = 1 ] && { META_USABLE_REASON=available; META_CLEANUP_CAPABILITY=test; }
        printf '%s|%s|%s|%s|%s\n' "$META_ENGINE" "$META_INSTALLED" "$META_ENABLED" "$META_USABLE" "$META_DETECTION_SOURCE"
        return 0
    fi

    META_ACTIVE_DIR=$(_ziyu_meta_active_dir)
    if [ -n "$META_ACTIVE_DIR" ]; then
        META_ENGINE=$(_ziyu_meta_type_for_dir "$META_ACTIVE_DIR")
        if [ -n "$META_ENGINE" ]; then
            META_MODULE_DIR="$META_ACTIVE_DIR"
            META_INSTALLED=1
            _ziyu_meta_enabled "$META_ACTIVE_DIR" && META_ENABLED=1
        else
            META_ENGINE=none
            META_ACTIVE_DIR=''
        fi
    fi

    if [ "$META_ENGINE" = none ]; then
        # One finite inventory per detection call instead of a complete module
        # directory traversal for every engine and enabled/disabled pass.
        # Matching candidates and disable/remove markers are rechecked on use.
        _lmd_installed_snapshot=$(_ziyu_meta_installed_snapshot)
        _lmd_installed_snapshot_ready=1
    fi

    # Prefer an enabled engine when no active metamodule link exists. A disabled
    # install remains visible in META_INSTALLED/META_ENABLED for diagnostics.
    # Mountify also runs as a standalone Magisk mount engine, where KernelSU's
    # /data/adb/metamodule selector symlink is intentionally absent. Check this
    # specific active Magisk configuration before inactive KSU metamodule installs.
    if [ "$META_ENGINE" = none ] && [ "${ROOT_MANAGER:-unknown}" = Magisk ]; then
        _lmd_candidate=$(_ziyu_meta_find_installed mountify 1)
        if [ -n "$_lmd_candidate" ]; then
            META_ENGINE=mountify
            META_MODULE_DIR="$_lmd_candidate"
            META_INSTALLED=1
            META_ENABLED=1
        fi
    fi
    if [ "$META_ENGINE" = none ]; then
        for _lmd_type in meta-overlayfs hybrid-mount mountify magic-mount nomount; do
            _lmd_candidate=$(_ziyu_meta_find_installed "$_lmd_type" 1)
            [ -n "$_lmd_candidate" ] || continue
            META_ENGINE="$_lmd_type"
            META_MODULE_DIR="$_lmd_candidate"
            META_INSTALLED=1
            META_ENABLED=1
            break
        done
    fi
    if [ "$META_ENGINE" = none ]; then
        for _lmd_type in meta-overlayfs hybrid-mount mountify magic-mount nomount; do
            _lmd_candidate=$(_ziyu_meta_find_installed "$_lmd_type" 0)
            [ -n "$_lmd_candidate" ] || continue
            META_ENGINE="$_lmd_type"
            META_MODULE_DIR="$_lmd_candidate"
            META_INSTALLED=1
            META_ENABLED=0
            break
        done
    fi
    META_AVAILABLE=$META_INSTALLED
    if [ "$META_INSTALLED" = 1 ] && [ "$META_ENABLED" = 0 ]; then
        META_USABLE_REASON=disabled
    elif [ "$META_INSTALLED" = 1 ] && [ "$META_ENABLED" = 1 ]; then
        META_USABLE_REASON=backend-not-active-or-runtime-unavailable
    fi

    if [ "$META_INSTALLED" = 1 ] && [ "$META_ENABLED" = 1 ]; then
        _lmd_flag=$(_ziyu_meta_prop "$META_MODULE_DIR" metamodule | tr '[:upper:]' '[:lower:]')
        _lmd_is_active=0
        _ziyu_meta_active_identity "$META_MODULE_DIR" && _lmd_is_active=1
        _lmd_module_dir="${MODDIR:-${MODULE_DIR:-/data/adb/modules/ziyu}}"
        _lmd_skip_mount_ok=1
        _lmd_skip_mountify_ok=1
        if [ -e "$_lmd_module_dir/skip_mount" ] && ! _ziyu_meta_is_owned_skip skip_mount; then
            _lmd_skip_mount_ok=0
        fi
        if [ -e "$_lmd_module_dir/skip_mountify" ] && ! _ziyu_meta_is_owned_skip skip_mountify; then
            _lmd_skip_mountify_ok=0
        fi
        case "$META_ENGINE" in
            meta-overlayfs)
                _lmd_overlay_flag=0; _lmd_overlay_hook=0; _lmd_overlay_image=0
                { [ "$_lmd_flag" = 1 ] || [ "$_lmd_flag" = true ]; } && _lmd_overlay_flag=1
                { [ -f "$META_MODULE_DIR/metamount.sh" ] || [ -x "$META_MODULE_DIR/meta-overlayfs" ] || [ -x "$META_MODULE_DIR/meta-overlay" ]; } && _lmd_overlay_hook=1
                { [ -f "$META_MODULE_DIR/modules.img" ] || [ -d "$META_MODULE_DIR/mnt" ] || _ziyu_meta_overlay_content_base >/dev/null; } && _lmd_overlay_image=1
                META_DETECTION_CHECKS="active=$_lmd_is_active,metamodule=$_lmd_overlay_flag,skip_ok=$_lmd_skip_mount_ok,hook=$_lmd_overlay_hook,image=$_lmd_overlay_image"
                if [ "$_lmd_is_active" -ne 1 ]; then META_USABLE_REASON=overlayfs-not-active
                elif [ "$_lmd_overlay_flag" != 1 ]; then META_USABLE_REASON=overlayfs-not-metamodule
                elif [ "$_lmd_skip_mount_ok" != 1 ]; then META_USABLE_REASON=overlayfs-module-excluded
                elif [ "$_lmd_overlay_hook" != 1 ]; then META_USABLE_REASON=overlayfs-hook-unavailable
                elif [ "$_lmd_overlay_image" != 1 ]; then META_USABLE_REASON=overlayfs-content-image-unavailable
                else
                # Same degraded acceptance as Hybrid without an unload API: the
                # engine mounts the payload; rollback on verification failure
                # means reboot, because meta mounts never survive one.
                META_READY=1
                META_USABLE=1
                META_USABLE_REASON=overlayfs-has-no-module-scoped-unload
                META_CLEANUP_CAPABILITY=none
                fi
                ;;
            hybrid-mount)
                _lmd_runtime=0
                [ -x "$META_MODULE_DIR/hybrid-mount" ] && _lmd_runtime=1
                [ -x "$META_MODULE_DIR/hybrid_mount" ] && _lmd_runtime=1
                if [ "$_lmd_is_active" -ne 1 ]; then
                    META_USABLE_REASON=hybrid-not-active
                elif ! { [ "$_lmd_flag" = 1 ] || [ "$_lmd_flag" = true ]; }; then
                    META_USABLE_REASON=hybrid-not-metamodule
                elif [ "$_lmd_runtime" -ne 1 ]; then
                    META_USABLE_REASON=hybrid-runtime-binary-unavailable
                elif [ "$_lmd_skip_mount_ok" -ne 1 ] || \
                     [ -e "$_lmd_module_dir/disable" ] || [ -e "$_lmd_module_dir/remove" ]; then
                    META_USABLE_REASON=hybrid-module-excluded
                else
                    if _ziyu_meta_hybrid_selected; then
                        META_READY=1
                        if _ziyu_meta_hybrid_runtime_api "$META_MODULE_DIR"; then
                            META_USABLE=1
                            META_USABLE_REASON=hybrid-pure-vfs-scoped-unload
                            META_CLEANUP_CAPABILITY=hybrid-vfs
                        else
                            # The engine mounts and verifies fine; only the
                            # module-scoped unload is missing. Meta still mounts
                            # (a reboot clears it), so accept with honest caveat
                            # instead of silently falling back to self-mount.
                            META_USABLE=1
                            META_USABLE_REASON=hybrid-runtime-api-unavailable
                            META_CLEANUP_CAPABILITY=none
                        fi
                    else
                        case "$META_HYBRID_ROUTE_REASON" in
                            hybrid-route-not-pure-vfs)
                                # Overlay/Magic routing is a valid mount path the
                                # user explicitly kept; it only loses per-module
                                # unload. Accept it and say so.
                                META_READY=1
                                META_USABLE=1
                                META_USABLE_REASON=hybrid-overlay-route
                                META_CLEANUP_CAPABILITY=none
                                ;;
                            *)
                                META_USABLE_REASON="$META_HYBRID_ROUTE_REASON"
                                ;;
                        esac
                    fi
                fi
                ;;
            mountify)
                _lmd_runner=0
                [ -x "$META_MODULE_DIR/post-fs-data.sh" ] && _lmd_runner=1
                [ -x "$META_MODULE_DIR/service.sh" ] && _lmd_runner=1
                # KernelSU/SukiSU executes an active Mountify metamodule through
                # metamount.sh; this script is sourced by the manager and does
                # not need the executable bit used by standalone boot hooks.
                if [ "$_lmd_is_active" -eq 1 ] && \
                   { [ "$_lmd_flag" = 1 ] || [ "$_lmd_flag" = true ]; } && \
                   [ -f "$META_MODULE_DIR/metamount.sh" ]; then
                    _lmd_runner=1
                fi
                if [ "$_lmd_is_active" -eq 1 ] || \
                   { [ "${ROOT_MANAGER:-unknown}" = Magisk ] && [ "$_lmd_runner" -eq 1 ]; }; then
                    if [ "$_lmd_runner" -ne 1 ]; then
                        META_USABLE_REASON=mountify-runtime-unavailable
                    elif [ "$_lmd_skip_mount_ok" -ne 1 ] || [ "$_lmd_skip_mountify_ok" -ne 1 ]; then
                        META_USABLE_REASON=mountify-module-excluded
                    elif _ziyu_meta_mountify_selected; then
                        META_READY=1
                        META_USABLE=1
                        META_USABLE_REASON=mountify-has-no-module-scoped-unload
                        META_CLEANUP_CAPABILITY=none
                    else
                        META_USABLE_REASON=mountify-module-excluded
                    fi
                fi
                ;;
            nomount)
                META_NOMOUNT_CLI=$(_ziyu_nomount_cli_for_dir "$META_MODULE_DIR")
                _lmd_runner=0
                [ -z "$META_NOMOUNT_CLI" ] || _lmd_runner=1
                if [ "$_lmd_is_active" -ne 1 ]; then
                    META_USABLE_REASON=nomount-not-active
                elif [ "$_lmd_runner" -ne 1 ]; then
                    META_USABLE_REASON=nomount-cli-unavailable
                elif [ "$_lmd_skip_mount_ok" -ne 1 ]; then
                    META_USABLE_REASON=nomount-module-excluded
                elif [ "${ZIYU_META_PROVIDER_SCAN:-0}" = 1 ]; then
                    # NoMount's metamount hook may load the VFS driver after this
                    # module's post-fs-data hook. Provider detection must not use
                    # an early `nm version` result as a capability verdict.
                    if [ -f "$META_MODULE_DIR/metamount.sh" ]; then
                        META_READY=1
                        META_USABLE_REASON=nomount-provider-ready
                    else
                        META_USABLE_REASON=nomount-provider-hook-unavailable
                    fi
                else
                    if ziyu_nomount_kernel_probe "$META_NOMOUNT_CLI"; then
                        META_READY=1
                        # The VFS interface is live, but this private-payload
                        # backend is intentionally separate from Meta selection.
                        META_USABLE_REASON=nomount-not-integrated
                    else
                        META_NOMOUNT_VERSION=''
                        META_USABLE_REASON=nomount-not-active
                    fi
                fi
                ;;
            magic-mount)
                _lmd_runner=0
                [ -x "$META_MODULE_DIR/meta-mm" ] && _lmd_runner=1
                [ -x "$META_MODULE_DIR/meta-mm-rs" ] && _lmd_runner=1
                [ -x "$META_MODULE_DIR/magic_mount_rs" ] && _lmd_runner=1
                [ -x "$META_MODULE_DIR/mmd" ] && _lmd_runner=1
                if [ "$_lmd_is_active" -ne 1 ]; then
                    META_USABLE_REASON=magic-mount-not-active
                elif ! { [ "$_lmd_flag" = 1 ] || [ "$_lmd_flag" = true ]; }; then
                    META_USABLE_REASON=magic-mount-not-metamodule
                elif [ "$_lmd_runner" -ne 1 ]; then
                    META_USABLE_REASON=magic-mount-runner-unavailable
                elif [ "$_lmd_skip_mount_ok" -ne 1 ]; then
                    META_USABLE_REASON=magic-mount-module-excluded
                else
                    META_READY=1
                    META_USABLE=1
                    META_USABLE_REASON=magic-mount-has-no-module-scoped-unload
                    META_CLEANUP_CAPABILITY=none
                fi
                ;;
        esac
    fi

    [ -n "$META_ENGINE" ] || META_ENGINE=none
    printf '%s|%s|%s|%s|%s\n' "$META_ENGINE" "$META_INSTALLED" "$META_ENABLED" "$META_USABLE" "$META_DETECTION_SOURCE"
}

ziyu_detect_mount_engine() {
    if [ "${ZIYU_META_FORCE_SELF:-0}" = 1 ]; then
        printf 'self-mount\n'
        return 0
    fi
    ziyu_meta_mount_detect >/dev/null
    if [ "$META_USABLE" = 1 ]; then
        printf '%s\n' "$META_ENGINE"
    else
        printf 'self-mount\n'
    fi
}

ziyu_mount_backend() {
    _lmb_engine="${1:-$(ziyu_detect_mount_engine)}"
    case "$_lmb_engine" in
        hybrid-mount) ziyu_hybrid_backend ;;
        meta-overlayfs|dual-dir-metamodule) printf 'overlayfs\n' ;;
        mountify) printf 'mountify\n' ;;
        nomount) printf 'nomount\n' ;;
        magic-mount|magic-mount-rs) printf 'magic-mount\n' ;;
        *) printf 'native\n' ;;
    esac
}

ziyu_meta_detection_info() {
    ziyu_meta_mount_detect >/dev/null
    printf 'META_ENGINE=%s\n' "$META_ENGINE"
    printf 'META_INSTALLED=%s\n' "$META_INSTALLED"
    printf 'META_AVAILABLE=%s\n' "$META_AVAILABLE"
    printf 'META_ENABLED=%s\n' "$META_ENABLED"
    printf 'META_USABLE=%s\n' "$META_USABLE"
    printf 'META_READY=%s\n' "$META_READY"
    printf 'META_USABLE_REASON=%s\n' "$META_USABLE_REASON"
    printf 'META_CLEANUP_CAPABILITY=%s\n' "$META_CLEANUP_CAPABILITY"
    printf 'META_DETECTION_SOURCE=%s\n' "$META_DETECTION_SOURCE"
    if [ "$META_ENGINE" = hybrid-mount ]; then
        printf 'META_HYBRID_CONFIG_PATH=%s\n' "$META_HYBRID_CONFIG_PATH"
        printf 'META_HYBRID_DEFAULT_MODE=%s\n' "$META_HYBRID_DEFAULT_MODE"
    fi
}
