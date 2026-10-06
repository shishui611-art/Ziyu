#!/system/bin/sh
# Detect external font mount engines without modifying their settings.
set +e

_luoshu_meta_root() {
    printf '%s\n' "${LUOSHU_META_DETECT_ROOT:-/data/adb}"
}

_luoshu_meta_prop() {
    sed -n "s/^$2=//p" "$1/module.prop" 2>/dev/null | head -n1 | tr -d '\r\n'
}

_luoshu_meta_truthy() {
    case "$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')" in
        1|true|yes|on|enabled) return 0 ;;
        *) return 1 ;;
    esac
}

_luoshu_meta_enabled() {
    [ -f "$1/module.prop" ] && [ ! -e "$1/disable" ] && [ ! -e "$1/remove" ]
}

_luoshu_meta_type_for_dir() {
    _lmtd_dir="$1"
    _lmtd_id=$(_luoshu_meta_prop "$_lmtd_dir" id | tr '[:upper:]' '[:lower:]')
    _lmtd_name=$(_luoshu_meta_prop "$_lmtd_dir" name | tr '[:upper:]' '[:lower:]')
    _lmtd_desc=$(_luoshu_meta_prop "$_lmtd_dir" description | tr '[:upper:]' '[:lower:]')
    case "$_lmtd_id $_lmtd_name $_lmtd_desc" in
        *meta-overlayfs*|*meta-overlay*) printf 'meta-overlayfs\n' ;;
        *mountify*) printf 'mountify\n' ;;
        *hybrid*mount*) printf 'hybrid-mount\n' ;;
        *nomount*) printf 'nomount\n' ;;
        *magic*mount*|*meta-mm*|*magic_mount_rs*) printf 'magic-mount\n' ;;
        *) return 1 ;;
    esac
}

_luoshu_meta_active_dir() {
    if [ -n "${LUOSHU_META_TEST_ACTIVE_DIR:-}" ]; then
        [ -f "$LUOSHU_META_TEST_ACTIVE_DIR/module.prop" ] || return 1
        printf '%s\n' "$LUOSHU_META_TEST_ACTIVE_DIR"
        return 0
    fi
    _lmad_meta="$(_luoshu_meta_root)/metamodule"
    [ -e "$_lmad_meta" ] || [ -L "$_lmad_meta" ] || return 1
    _lmad_target=$(readlink -f "$_lmad_meta" 2>/dev/null)
    [ -n "$_lmad_target" ] || _lmad_target="$_lmad_meta"
    [ -f "$_lmad_target/module.prop" ] || return 1
    printf '%s\n' "$_lmad_target"
}

_luoshu_meta_find_installed() {
    _lmfi_root="$(_luoshu_meta_root)/modules"
    _lmfi_type="$1"
    _lmfi_require_enabled="${2:-0}"
    for _lmfi_dir in "$_lmfi_root"/*; do
        [ -d "$_lmfi_dir" ] || continue
        _lmfi_detected=$(_luoshu_meta_type_for_dir "$_lmfi_dir") || continue
        [ "$_lmfi_detected" = "$_lmfi_type" ] || continue
        if [ "$_lmfi_require_enabled" = 1 ]; then
            _luoshu_meta_enabled "$_lmfi_dir" || continue
        fi
        printf '%s\n' "$_lmfi_dir"
        return 0
    done
    return 1
}

_luoshu_meta_is_owned_skip() {
    _lmis_root="${MODDIR:-${MODULE_DIR:-/data/adb/modules/LuoShu}}"
    [ -f "$_lmis_root/config/self-mount-owned" ]
}

_luoshu_meta_mountify_selected() {
    _lmms_root="$(_luoshu_meta_root)"
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
            grep -Fxq 'LuoShu' "$_lmms_list" 2>/dev/null || return 1
            ;;
        2) ;;
        *) return 1 ;;
    esac
    _lmms_module="${MODDIR:-${MODULE_DIR:-/data/adb/modules/LuoShu}}"
    if [ -e "$_lmms_module/skip_mountify" ] && ! _luoshu_meta_is_owned_skip; then
        return 1
    fi
    if [ -e "$_lmms_module/skip_mount" ] && ! _luoshu_meta_is_owned_skip; then
        return 1
    fi
    return 0
}

_luoshu_meta_hybrid_selected() {
    _lmhs_root="$(_luoshu_meta_root)"
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
    _lmhs_id=$(_luoshu_meta_prop "${MODDIR:-${MODULE_DIR:-/data/adb/modules/LuoShu}}" id)
    [ -n "$_lmhs_id" ] || _lmhs_id=LuoShu
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
    # an unreadable or unrecognized blacklist must never silently allow LuoShu.
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

_luoshu_meta_hybrid_route_state() {
    # Same conservative grammar as _luoshu_meta_hybrid_selected, but reports why
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

luoshu_meta_hybrid_ensure_vfs_rule() {
    # Users must not need to edit Hybrid Mount TOML by hand: when they pick the
    # Meta backend and the only blocker is the upstream Overlay default, add a
    # LuoShu-scoped VFS rule ourselves. Explicit per-module user rules win and
    # are never overwritten; the write is backed up and verified by readback.
    LUOSHU_META_ENSURE_RESULT=error
    LUOSHU_META_ENSURE_DETAIL=''
    [ "${META_ENGINE:-none}" = hybrid-mount ] && [ -n "${META_MODULE_DIR:-}" ] || {
        LUOSHU_META_ENSURE_RESULT=not-hybrid; return 1
    }
    [ "${META_HYBRID_ROUTE_REASON:-}" = hybrid-route-not-pure-vfs ] || {
        LUOSHU_META_ENSURE_RESULT=not-needed
        LUOSHU_META_ENSURE_DETAIL="${META_HYBRID_ROUTE_REASON:-unknown}"
        return 1
    }
    [ -n "${META_HYBRID_CONFIG_PATH:-}" ] || {
        LUOSHU_META_ENSURE_RESULT=not-needed
        LUOSHU_META_ENSURE_DETAIL=hybrid-config-missing
        return 1
    }
    _lmev_file="$META_HYBRID_CONFIG_PATH"
    [ -r "$_lmev_file" ] || { LUOSHU_META_ENSURE_RESULT=config-unreadable; return 1; }
    _lmev_id=$(_luoshu_meta_prop "${MODDIR:-${MODULE_DIR:-/data/adb/modules/LuoShu}}" id)
    [ -n "$_lmev_id" ] || _lmev_id=LuoShu
    if command grep -q 'ziyu-luoshu-vfs-rule' "$_lmev_file" 2>/dev/null; then
        LUOSHU_META_ENSURE_RESULT=already
        return 0
    fi
    _lmev_state=$(_luoshu_meta_hybrid_route_state "$_lmev_id" "$_lmev_file")
    IFS='|' read -r _lmev_kind _lmev_global _lmev_module <<EOF_LMEV
$_lmev_state
EOF_LMEV
    case "$_lmev_kind" in
        invalid|'')
            LUOSHU_META_ENSURE_RESULT=config-unverified; return 1 ;;
        module-paths-unsafe|module-overlay|module-magic|module-ignore|module-section-empty|paths-only-safe)
            LUOSHU_META_ENSURE_RESULT=explicit-rule-wins
            LUOSHU_META_ENSURE_DETAIL="$_lmev_kind"
            return 2 ;;
        module-vfs)
            LUOSHU_META_ENSURE_RESULT=not-needed; return 1 ;;
        no-module) ;;
        *)
            LUOSHU_META_ENSURE_RESULT=config-unverified; return 1 ;;
    esac
    _lmev_backup="$_lmev_file.luoshu-backup"
    _lmev_tmp="$_lmev_backup.tmp.$$"
    cp "$_lmev_file" "$_lmev_tmp" 2>/dev/null || { LUOSHU_META_ENSURE_RESULT=backup-write-failed; return 1; }
    mv -f "$_lmev_tmp" "$_lmev_backup" 2>/dev/null || {
        rm -f "$_lmev_tmp"
        LUOSHU_META_ENSURE_RESULT=backup-write-failed; return 1
    }
    {
        printf '\n# >>> ziyu-luoshu-vfs-rule (added by the Ziyu font module for scoped unload)\n'
        printf '[rules.LuoShu]\ndefault_mode = "vfs"\n'
        printf '# <<< ziyu-luoshu-vfs-rule\n'
    } >> "$_lmev_file" 2>/dev/null || {
        cat "$_lmev_backup" > "$_lmev_file" 2>/dev/null
        LUOSHU_META_ENSURE_RESULT=rule-write-failed; return 1
    }
    if _luoshu_meta_hybrid_selected; then
        LUOSHU_META_ENSURE_RESULT=applied
        LUOSHU_META_ENSURE_DETAIL="$_lmev_backup"
        return 0
    fi
    cat "$_lmev_backup" > "$_lmev_file" 2>/dev/null
    LUOSHU_META_ENSURE_RESULT=verify-failed
    return 1
}

_luoshu_meta_active_identity() {
    [ "${LUOSHU_META_TEST_ASSUME_ACTIVE:-0}" = 1 ] && return 0
    _lmai_dir="$1"
    _lmai_root="$(_luoshu_meta_root)"
    _lmai_link="$_lmai_root/metamodule"
    [ -L "$_lmai_link" ] || return 1
    _lmai_resolved=$(readlink -f "$_lmai_link" 2>/dev/null)
    [ -n "$_lmai_resolved" ] || return 1
    [ "$_lmai_resolved" = "$(readlink -f "$_lmai_dir" 2>/dev/null)" ]
}

_luoshu_meta_hybrid_runtime_api() {
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

luoshu_meta_mount_detect() {
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
    META_DETECTION_SOURCE=filesystem
    META_HYBRID_CONFIG_PATH=''
    META_HYBRID_DEFAULT_MODE=''
    META_HYBRID_ROUTE_REASON=''

    if [ -n "${LUOSHU_META_TEST_ENGINE:-}" ]; then
        META_ENGINE="$LUOSHU_META_TEST_ENGINE"
        META_INSTALLED=${LUOSHU_META_TEST_INSTALLED:-1}
        META_ENABLED=${LUOSHU_META_TEST_ENABLED:-1}
        META_USABLE=${LUOSHU_META_TEST_USABLE:-1}
        META_READY=${LUOSHU_META_TEST_READY:-$META_USABLE}
        META_AVAILABLE=$META_INSTALLED
        META_DETECTION_SOURCE=test
        [ "$META_USABLE" = 1 ] && { META_USABLE_REASON=available; META_CLEANUP_CAPABILITY=test; }
        printf '%s|%s|%s|%s|%s\n' "$META_ENGINE" "$META_INSTALLED" "$META_ENABLED" "$META_USABLE" "$META_DETECTION_SOURCE"
        return 0
    fi

    META_ACTIVE_DIR=$(_luoshu_meta_active_dir)
    if [ -n "$META_ACTIVE_DIR" ]; then
        META_ENGINE=$(_luoshu_meta_type_for_dir "$META_ACTIVE_DIR")
        if [ -n "$META_ENGINE" ]; then
            META_MODULE_DIR="$META_ACTIVE_DIR"
            META_INSTALLED=1
            _luoshu_meta_enabled "$META_ACTIVE_DIR" && META_ENABLED=1
        else
            META_ENGINE=none
            META_ACTIVE_DIR=''
        fi
    fi

    # Prefer an enabled engine when no active metamodule link exists. A disabled
    # install remains visible in META_INSTALLED/META_ENABLED for diagnostics.
    # Mountify also runs as a standalone Magisk mount engine, where KernelSU's
    # /data/adb/metamodule selector symlink is intentionally absent. Check this
    # specific active Magisk configuration before inactive KSU metamodule installs.
    if [ "$META_ENGINE" = none ] && [ "${ROOT_MANAGER:-unknown}" = Magisk ]; then
        _lmd_candidate=$(_luoshu_meta_find_installed mountify 1)
        if [ -n "$_lmd_candidate" ]; then
            META_ENGINE=mountify
            META_MODULE_DIR="$_lmd_candidate"
            META_INSTALLED=1
            META_ENABLED=1
        fi
    fi
    if [ "$META_ENGINE" = none ]; then
        for _lmd_type in meta-overlayfs hybrid-mount mountify magic-mount; do
            _lmd_candidate=$(_luoshu_meta_find_installed "$_lmd_type" 1)
            [ -n "$_lmd_candidate" ] || continue
            META_ENGINE="$_lmd_type"
            META_MODULE_DIR="$_lmd_candidate"
            META_INSTALLED=1
            META_ENABLED=1
            break
        done
    fi
    if [ "$META_ENGINE" = none ]; then
        for _lmd_type in meta-overlayfs hybrid-mount mountify magic-mount; do
            _lmd_candidate=$(_luoshu_meta_find_installed "$_lmd_type" 0)
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
        _lmd_flag=$(_luoshu_meta_prop "$META_MODULE_DIR" metamodule | tr '[:upper:]' '[:lower:]')
        _lmd_is_active=0
        _luoshu_meta_active_identity "$META_MODULE_DIR" && _lmd_is_active=1
        _lmd_module_dir="${MODDIR:-${MODULE_DIR:-/data/adb/modules/LuoShu}}"
        _lmd_skip_mount_ok=1
        _lmd_skip_mountify_ok=1
        if [ -e "$_lmd_module_dir/skip_mount" ] && ! _luoshu_meta_is_owned_skip; then
            _lmd_skip_mount_ok=0
        fi
        if [ -e "$_lmd_module_dir/skip_mountify" ] && ! _luoshu_meta_is_owned_skip; then
            _lmd_skip_mountify_ok=0
        fi
        case "$META_ENGINE" in
            meta-overlayfs)
                if [ "$_lmd_is_active" -eq 1 ] && \
                { [ "$_lmd_flag" = 1 ] || [ "$_lmd_flag" = true ]; } && [ "$_lmd_skip_mount_ok" -eq 1 ] && \
                { [ -f "$META_MODULE_DIR/metamount.sh" ] || [ -x "$META_MODULE_DIR/meta-overlayfs" ] || [ -x "$META_MODULE_DIR/meta-overlay" ]; } && \
                { [ -f "$META_MODULE_DIR/modules.img" ] || [ -d "$META_MODULE_DIR/mnt" ]; }; then
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
                    if _luoshu_meta_hybrid_selected; then
                        META_READY=1
                        if _luoshu_meta_hybrid_runtime_api "$META_MODULE_DIR"; then
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
                if [ "$_lmd_is_active" -eq 1 ] || \
                   { [ "${ROOT_MANAGER:-unknown}" = Magisk ] && [ "$_lmd_runner" -eq 1 ]; }; then
                    if [ "$_lmd_runner" -ne 1 ]; then
                        META_USABLE_REASON=mountify-runtime-unavailable
                    elif [ "$_lmd_skip_mount_ok" -ne 1 ] || [ "$_lmd_skip_mountify_ok" -ne 1 ]; then
                        META_USABLE_REASON=mountify-module-excluded
                    elif _luoshu_meta_mountify_selected; then
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
                _lmd_runner=0
                [ -x "$META_MODULE_DIR/nm" ] && _lmd_runner=1
                [ -x "$META_MODULE_DIR/nomount" ] && _lmd_runner=1
                if [ "$_lmd_is_active" -ne 1 ]; then
                    META_USABLE_REASON=nomount-not-active
                elif [ "$_lmd_runner" -ne 1 ]; then
                    META_USABLE_REASON=nomount-cli-unavailable
                elif [ "$_lmd_skip_mount_ok" -ne 1 ]; then
                    META_USABLE_REASON=nomount-module-excluded
                else
                    # NoMount serves standard module system dirs via kernel VFS
                    # injection, but Ziyu's font payload lives outside the
                    # module system dir, so automatic injection covers nothing
                    # for fonts yet. Report the engine honestly; selecting meta
                    # would mount nothing until rule-based integration lands.
                    META_READY=1
                    META_USABLE_REASON=nomount-not-integrated
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

luoshu_detect_mount_engine() {
    if [ "${LUOSHU_META_FORCE_SELF:-0}" = 1 ]; then
        printf 'self-mount\n'
        return 0
    fi
    luoshu_meta_mount_detect >/dev/null
    if [ "$META_USABLE" = 1 ]; then
        printf '%s\n' "$META_ENGINE"
    else
        printf 'self-mount\n'
    fi
}

luoshu_mount_backend() {
    _lmb_engine="${1:-$(luoshu_detect_mount_engine)}"
    case "$_lmb_engine" in
        hybrid-mount) luoshu_hybrid_backend ;;
        meta-overlayfs|dual-dir-metamodule) printf 'overlayfs\n' ;;
        mountify) printf 'mountify\n' ;;
        nomount) printf 'nomount\n' ;;
        magic-mount|magic-mount-rs) printf 'magic-mount\n' ;;
        *) printf 'native\n' ;;
    esac
}

luoshu_meta_detection_info() {
    luoshu_meta_mount_detect >/dev/null
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
