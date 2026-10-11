#!/system/bin/sh
# Transactional NoMount rules for Ziyu's private physical payload only.
set +e

_lnm_module() { printf '%s\n' "${1:-${MODDIR:-${MODULE_DIR:-/data/adb/modules/Ziyu}}}"; }
_lnm_state_dir() { printf '%s/.ziyu-state\n' "$(_lnm_module "$1")"; }
_lnm_ledger() { printf '%s/nomount-rules.current\n' "$(_lnm_state_dir "$1")"; }
_lnm_boot_id() {
    printf '%s\n' "${LUOSHU_NOMOUNT_TEST_BOOT_ID:-${LUOSHU_BACKEND_TEST_BOOT_ID:-$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')}}"
}
_lnm_log() {
    _lnm_log_module="$1"
    mkdir -p "$_lnm_log_module/logs" 2>/dev/null || true
    printf '[%s] [NoMount] %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)" "$2" \
        >> "$_lnm_log_module/logs/mount-backend.log" 2>/dev/null || true
}

_lnm_cli() {
    [ -x "${META_NOMOUNT_CLI:-}" ] && { printf '%s\n' "$META_NOMOUNT_CLI"; return 0; }
    _lnm_meta="${META_MODULE_DIR:-}"
    if [ -n "$_lnm_meta" ]; then
        for _lnm_candidate in "$_lnm_meta/bin/nm" "$_lnm_meta/nm" "$_lnm_meta/nomount"; do
            [ -x "$_lnm_candidate" ] || continue
            printf '%s\n' "$_lnm_candidate"
            return 0
        done
    fi
    # The soft-reboot hook can run without provider detection having populated
    # META_MODULE_DIR. Locate only a module whose declared id is NoMount.
    for _lnm_candidate_dir in /data/adb/modules/nomount /data/adb/modules/NoMount /data/adb/modules/*; do
        [ -d "$_lnm_candidate_dir" ] || continue
        _lnm_candidate_id=$(sed -n 's/^id=//p' "$_lnm_candidate_dir/module.prop" 2>/dev/null | head -n1 | tr '[:upper:]' '[:lower:]')
        [ "$_lnm_candidate_id" = nomount ] || continue
        for _lnm_candidate in "$_lnm_candidate_dir/bin/nm" "$_lnm_candidate_dir/nm" "$_lnm_candidate_dir/nomount"; do
            [ -x "$_lnm_candidate" ] || continue
            printf '%s\n' "$_lnm_candidate"
            return 0
        done
    done
    return 1
}

_lnm_json_parse() {
    _lnm_module="$1"
    _lnm_helper="$_lnm_module/common/nomount_rule_json.py"
    [ -f "$_lnm_helper" ] || return 1
    _lnm_python="${LUOSHU_NOMOUNT_JSON_PYTHON:-${LUOSHU_PYTHON:-}}"
    _lnm_bundled=0
    if [ -z "$_lnm_python" ]; then
        _lnm_python="$_lnm_module/common/python/bin/luoshu-python"
        [ -x "$_lnm_python" ] && _lnm_bundled=1
    fi
    if [ ! -x "$_lnm_python" ]; then
        if command -v python3 >/dev/null 2>&1; then _lnm_python=$(command -v python3)
        elif command -v python >/dev/null 2>&1; then _lnm_python=$(command -v python)
        else return 1
        fi
    fi
    # Percent-encode slashes before passing paths through the environment. This
    # keeps Git Bash/MSYS from rewriting Android virtual paths for host Python.
    LUOSHU_NOMOUNT_JSON_TARGET=$(printf '%s' "$3" | sed 's/%/%25/g; s|/|%2F|g')
    LUOSHU_NOMOUNT_JSON_SOURCE=$(printf '%s' "${4:-}" | sed 's/%/%25/g; s|/|%2F|g')
    LUOSHU_NOMOUNT_JSON_ROOT=$(printf '%s' "$3" | sed 's/%/%25/g; s|/|%2F|g')
    export LUOSHU_NOMOUNT_JSON_TARGET LUOSHU_NOMOUNT_JSON_SOURCE LUOSHU_NOMOUNT_JSON_ROOT
    if [ "$_lnm_bundled" = 1 ]; then
        _lnm_pyroot="$_lnm_module/common/python"
        if [ "$2" = pair ]; then
            PYTHONHOME="$_lnm_pyroot" \
            PYTHONPATH="$_lnm_module/common:$_lnm_pyroot/lib/python3.14:$_lnm_pyroot/lib/python3.14/site-packages" \
            LD_LIBRARY_PATH="$_lnm_pyroot/lib:$_lnm_pyroot/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
                "$_lnm_python" "$_lnm_helper" "$2" 2>>"$_lnm_module/logs/mount-backend.log"
        else
            PYTHONHOME="$_lnm_pyroot" \
            PYTHONPATH="$_lnm_module/common:$_lnm_pyroot/lib/python3.14:$_lnm_pyroot/lib/python3.14/site-packages" \
            LD_LIBRARY_PATH="$_lnm_pyroot/lib:$_lnm_pyroot/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
                "$_lnm_python" "$_lnm_helper" "$2" 2>>"$_lnm_module/logs/mount-backend.log"
        fi
    else
        if [ "$2" = pair ]; then
            "$_lnm_python" "$_lnm_helper" "$2" 2>>"$_lnm_module/logs/mount-backend.log"
        else
            "$_lnm_python" "$_lnm_helper" "$2" 2>>"$_lnm_module/logs/mount-backend.log"
        fi
    fi
}

_lnm_rule_state() {
    _lnm_module="$1"
    _lnm_cli_path="$2"
    _lnm_mode="$3"
    _lnm_target="$4"
    _lnm_source="${5:-}"
    _lnm_json=$("$_lnm_cli_path" rule list --json 2>>"$_lnm_module/logs/mount-backend.log") || return 1
    _lnm_state=$(printf '%s\n' "$_lnm_json" | _lnm_json_parse "$_lnm_module" "$_lnm_mode" "$_lnm_target" "$_lnm_source") || return 1
    case "$_lnm_state" in present|absent|exact|conflict) printf '%s\n' "$_lnm_state" ;; *) return 1 ;; esac
}

luoshu_nomount_module_rules_state() {
    _lnm_module=$(_lnm_module "${1:-}")
    _lnm_cli_path=$(_lnm_cli) || return 2
    mkdir -p "$_lnm_module/logs" 2>/dev/null || return 2
    _lnm_state=$(_lnm_rule_state "$_lnm_module" "$_lnm_cli_path" root "$_lnm_module") || return 2
    case "$_lnm_state" in present|absent) printf '%s\n' "$_lnm_state" ;; *) return 2 ;; esac
}

_lnm_hash() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" 2>/dev/null | awk '{print tolower($1)}'
    elif command -v busybox >/dev/null 2>&1; then
        busybox sha256sum "$1" 2>/dev/null | awk '{print tolower($1)}'
    else
        return 1
    fi
}

_lnm_partition_allowed() {
    case "$1" in
        system|system_ext|product|vendor|odm|oem|my_product|my_engineering|my_company|my_preload|my_region|my_stock|oplus_product|oplus_engineering|oplus_version|oplus_region|mi_ext|cust|hw_product|oplus) return 0 ;;
        *) return 1 ;;
    esac
}

_lnm_target_for_rel() {
    _lnm_rel="$1"
    case "$_lnm_rel" in ''|/*|../*|*/../*|*/..|*'|'*|*'\'*|*"
"*) return 1 ;; esac
    _lnm_partition=${_lnm_rel%%/*}
    [ "$_lnm_partition" != "$_lnm_rel" ] || return 1
    _lnm_rest=${_lnm_rel#*/}
    [ -n "$_lnm_rest" ] || return 1
    _lnm_partition_allowed "$_lnm_partition" || return 1
    if [ "$_lnm_partition" = system ]; then
        _lnm_first=${_lnm_rest%%/*}
        case "$_lnm_first" in
            vendor|system_ext|product|odm|apex|oem|optics|prism|mi_ext|my_*)
                _lnm_tail=${_lnm_rest#*/}
                [ -n "$_lnm_tail" ] && { printf '/%s/%s\n' "$_lnm_first" "$_lnm_tail"; return 0; }
                ;;
        esac
        printf '/system/%s\n' "$_lnm_rest"
    else
        printf '/%s/%s\n' "$_lnm_partition" "$_lnm_rest"
    fi
}

_lnm_write_header() {
    _lnm_ledger_file="$1"
    _lnm_state="$2"
    _lnm_boot="$3"
    _lnm_generation="$4"
    _lnm_tmp="${_lnm_ledger_file}.tmp.$$"
    {
        printf 'schema=ziyu-nomount-rules-v1\n'
        printf 'state=%s\n' "$_lnm_state"
        printf 'boot_id=%s\n' "$_lnm_boot"
        printf 'payload_generation=%s\n' "$_lnm_generation"
    } > "$_lnm_tmp" 2>/dev/null || return 1
    chmod 0600 "$_lnm_tmp" 2>/dev/null || true
    mv -f "$_lnm_tmp" "$_lnm_ledger_file" 2>/dev/null || { rm -f "$_lnm_tmp"; return 1; }
}

_lnm_set_ledger_state() {
    _lnm_ledger_file="$1"
    _lnm_state="$2"
    _lnm_tmp="${_lnm_ledger_file}.tmp.$$"
    [ -f "$_lnm_ledger_file" ] || return 1
    awk -v state="$_lnm_state" '
        /^state=/ { print "state=" state; state_written=1; next }
        { print }
        END { if (!state_written) exit 1 }
    ' "$_lnm_ledger_file" > "$_lnm_tmp" 2>/dev/null || { rm -f "$_lnm_tmp"; return 1; }
    chmod 0600 "$_lnm_tmp" 2>/dev/null || true
    mv -f "$_lnm_tmp" "$_lnm_ledger_file" 2>/dev/null || { rm -f "$_lnm_tmp"; return 1; }
}

_lnm_append_ledger_rule() {
    _lnm_ledger_file="$1"
    _lnm_boot="$2"
    _lnm_generation="$3"
    _lnm_target="$4"
    _lnm_source="$5"
    _lnm_tmp="${_lnm_ledger_file}.tmp.$$"
    cat "$_lnm_ledger_file" > "$_lnm_tmp" 2>/dev/null || return 1
    printf '%s|%s|%s|%s\n' "$_lnm_boot" "$_lnm_generation" "$_lnm_target" "$_lnm_source" \
        >> "$_lnm_tmp" 2>/dev/null || { rm -f "$_lnm_tmp"; return 1; }
    chmod 0600 "$_lnm_tmp" 2>/dev/null || true
    mv -f "$_lnm_tmp" "$_lnm_ledger_file" 2>/dev/null || { rm -f "$_lnm_tmp"; return 1; }
}

_lnm_cleanup_rows() {
    _lnm_module="$1"
    _lnm_ledger_file="$2"
    _lnm_cli_path="$3"
    _lnm_boot="$4"
    _lnm_bad=0
    [ -f "$_lnm_ledger_file" ] || return 0
    _lnm_saved_boot=$(sed -n 's/^boot_id=//p' "$_lnm_ledger_file" | head -n1 | tr -d '\r\n')
    if [ -z "$_lnm_saved_boot" ] || [ "$_lnm_saved_boot" != "$_lnm_boot" ]; then
        rm -f "$_lnm_ledger_file" "${_lnm_ledger_file}.tmp."* 2>/dev/null || true
        return 0
    fi
    while IFS='|' read -r _lnm_row_boot _lnm_generation _lnm_target _lnm_source; do
        [ -n "$_lnm_row_boot" ] || continue
        case "$_lnm_row_boot" in
            schema=*|state=*|boot_id=*|payload_generation=*) continue ;;
        esac
        [ -n "$_lnm_generation" ] && [ -n "$_lnm_target" ] && [ -n "$_lnm_source" ] || { _lnm_bad=1; continue; }
        [ "$_lnm_row_boot" = "$_lnm_boot" ] || { _lnm_bad=1; continue; }
        case "$_lnm_target" in /*) ;; *) _lnm_bad=1; continue ;; esac
        case "$_lnm_source" in "$_lnm_module/.luoshu-payload/"*) ;; *) _lnm_bad=1; continue ;; esac
        _lnm_rule_state_value=$(_lnm_rule_state "$_lnm_module" "$_lnm_cli_path" pair "$_lnm_target" "$_lnm_source") || { _lnm_bad=1; continue; }
        case "$_lnm_rule_state_value" in
            exact)
                "$_lnm_cli_path" rule del "$_lnm_target" >>"$_lnm_module/logs/mount-backend.log" 2>&1 || { _lnm_bad=1; continue; }
                _lnm_after=$(_lnm_rule_state "$_lnm_module" "$_lnm_cli_path" pair "$_lnm_target" "$_lnm_source") || { _lnm_bad=1; continue; }
                [ "$_lnm_after" != exact ] || _lnm_bad=1
                ;;
            conflict|absent) ;;
            *) _lnm_bad=1 ;;
        esac
    done < "$_lnm_ledger_file"
    [ "$_lnm_bad" -eq 0 ] || return 1
    rm -f "$_lnm_ledger_file" "${_lnm_ledger_file}.tmp."* 2>/dev/null || return 1
    return 0
}

luoshu_nomount_cleanup() {
    _lnm_module=$(_lnm_module "${1:-}")
    _lnm_ledger_file=$(_lnm_ledger "$_lnm_module")
    [ -f "$_lnm_ledger_file" ] || return 0
    _lnm_saved_boot=$(sed -n 's/^boot_id=//p' "$_lnm_ledger_file" 2>/dev/null | head -n1 | tr -d '\r\n')
    _lnm_boot=$(_lnm_boot_id)
    if [ -z "$_lnm_saved_boot" ] || [ "$_lnm_saved_boot" != "$_lnm_boot" ]; then
        rm -f "$_lnm_ledger_file" "${_lnm_ledger_file}.tmp."* 2>/dev/null || true
        return 0
    fi
    _lnm_cli_path=$(_lnm_cli) || return 1
    mkdir -p "$_lnm_module/logs" 2>/dev/null || true
    _lnm_cleanup_rows "$_lnm_module" "$_lnm_ledger_file" "$_lnm_cli_path" "$_lnm_boot"
}

luoshu_nomount_apply() (
    _lnm_module=$(_lnm_module "${1:-}")
    _lnm_payload="$_lnm_module/.luoshu-payload"
    _lnm_manifest="$_lnm_module/config/font-payload-manifest.conf"
    _lnm_state_dir=$(_lnm_state_dir "$_lnm_module")
    _lnm_ledger_file=$(_lnm_ledger "$_lnm_module")
    _lnm_boot=$(_lnm_boot_id)
    _lnm_plan="$_lnm_state_dir/.nomount-plan.$$"
    _lnm_cli_path=$(_lnm_cli) || { _lnm_log "$_lnm_module" 'nm-cli-unavailable'; return 1; }
    [ -n "$_lnm_boot" ] || { _lnm_log "$_lnm_module" 'boot-id-unavailable'; return 1; }
    [ -s "$_lnm_manifest" ] && [ -d "$_lnm_payload" ] || { _lnm_log "$_lnm_module" 'payload-manifest-unavailable'; return 1; }
    mkdir -p "$_lnm_state_dir" "$_lnm_module/logs" 2>/dev/null || return 1
    luoshu_nomount_cleanup "$_lnm_module" || { _lnm_log "$_lnm_module" 'previous-rule-cleanup-dirty'; return 2; }

    _lnm_generation=$(_lnm_hash "$_lnm_manifest") || return 1
    case "$_lnm_generation" in *[!0-9a-f]*) return 1 ;; esac
    [ "${#_lnm_generation}" -eq 64 ] || return 1
    _lnm_payload_real=$(readlink -f "$_lnm_payload" 2>/dev/null)
    [ -n "$_lnm_payload_real" ] || return 1
    : > "$_lnm_plan" 2>/dev/null || return 1
    trap 'rm -f "$_lnm_plan"' EXIT HUP INT TERM
    _lnm_system_fonts=0
    while IFS='|' read -r _lnm_rel _lnm_expected_hash; do
        [ -n "$_lnm_rel" ] || continue
        case "$_lnm_expected_hash" in *[!0-9a-fA-F]*) rm -f "$_lnm_plan"; return 1 ;; esac
        [ "${#_lnm_expected_hash}" -eq 64 ] || { rm -f "$_lnm_plan"; return 1; }
        _lnm_source="$_lnm_payload/$_lnm_rel"
        case "$_lnm_rel" in ''|/*|../*|*/../*|*/..|*'|'*|*'\'*|*"
"*) rm -f "$_lnm_plan"; return 1 ;; esac
        [ -f "$_lnm_source" ] && [ -r "$_lnm_source" ] || { rm -f "$_lnm_plan"; return 1; }
        _lnm_source_real=$(readlink -f "$_lnm_source" 2>/dev/null)
        case "$_lnm_source_real" in "$_lnm_payload_real"/*) ;; *) rm -f "$_lnm_plan"; return 1 ;; esac
        _lnm_actual_hash=$(_lnm_hash "$_lnm_source") || { rm -f "$_lnm_plan"; return 1; }
        [ "$(printf '%s' "$_lnm_actual_hash" | tr '[:upper:]' '[:lower:]')" = "$(printf '%s' "$_lnm_expected_hash" | tr '[:upper:]' '[:lower:]')" ] || { rm -f "$_lnm_plan"; return 1; }
        _lnm_target=$(_lnm_target_for_rel "$_lnm_rel") || { rm -f "$_lnm_plan"; return 1; }
        case "$_lnm_target" in /*) ;; *) rm -f "$_lnm_plan"; return 1 ;; esac
        case "$_lnm_rel" in system/fonts/*) _lnm_system_fonts=1 ;; esac
        printf '%s|%s\n' "$_lnm_target" "$_lnm_source_real" >> "$_lnm_plan" || { rm -f "$_lnm_plan"; return 1; }
    done < "$_lnm_manifest"
    [ "$_lnm_system_fonts" -eq 1 ] && [ -s "$_lnm_plan" ] || { rm -f "$_lnm_plan"; return 1; }
    _lnm_duplicates=$(awk -F'|' '{ if (seen[$1]++) bad=1 } END { exit !bad }' "$_lnm_plan" 2>/dev/null; printf '%s' "$?")
    [ "$_lnm_duplicates" = 1 ] || { rm -f "$_lnm_plan"; return 1; }

    _lnm_write_header "$_lnm_ledger_file" applying "$_lnm_boot" "$_lnm_generation" || { rm -f "$_lnm_plan"; return 1; }
    while IFS='|' read -r _lnm_target _lnm_source; do
        _lnm_existing=$(_lnm_rule_state "$_lnm_module" "$_lnm_cli_path" target "$_lnm_target") || {
            _lnm_cleanup_rows "$_lnm_module" "$_lnm_ledger_file" "$_lnm_cli_path" "$_lnm_boot" || { rm -f "$_lnm_plan"; return 2; }
            rm -f "$_lnm_plan"
            return 1
        }
        if [ "$_lnm_existing" = present ]; then
            _lnm_log "$_lnm_module" "target-conflict target=$_lnm_target"
            _lnm_cleanup_rows "$_lnm_module" "$_lnm_ledger_file" "$_lnm_cli_path" "$_lnm_boot" || { rm -f "$_lnm_plan"; return 2; }
            rm -f "$_lnm_plan"
            return 1
        fi
        if ! "$_lnm_cli_path" rule add "$_lnm_target" "$_lnm_source" >>"$_lnm_module/logs/mount-backend.log" 2>&1; then
            _lnm_failed_state=$(_lnm_rule_state "$_lnm_module" "$_lnm_cli_path" pair "$_lnm_target" "$_lnm_source") || { rm -f "$_lnm_plan"; return 2; }
            if [ "$_lnm_failed_state" = exact ]; then
                _lnm_append_ledger_rule "$_lnm_ledger_file" "$_lnm_boot" "$_lnm_generation" "$_lnm_target" "$_lnm_source" || { rm -f "$_lnm_plan"; return 2; }
            fi
            _lnm_cleanup_rows "$_lnm_module" "$_lnm_ledger_file" "$_lnm_cli_path" "$_lnm_boot" || { rm -f "$_lnm_plan"; return 2; }
            rm -f "$_lnm_plan"
            _lnm_log "$_lnm_module" "rule-add-failed target=$_lnm_target; clean rollback complete"
            return 1
        fi
        _lnm_append_ledger_rule "$_lnm_ledger_file" "$_lnm_boot" "$_lnm_generation" "$_lnm_target" "$_lnm_source" || {
            _lnm_after_add=$(_lnm_rule_state "$_lnm_module" "$_lnm_cli_path" pair "$_lnm_target" "$_lnm_source")
            [ "$_lnm_after_add" != exact ] || "$_lnm_cli_path" rule del "$_lnm_target" >>"$_lnm_module/logs/mount-backend.log" 2>&1 || true
            rm -f "$_lnm_plan"
            return 2
        }
        _lnm_verify=$(_lnm_rule_state "$_lnm_module" "$_lnm_cli_path" pair "$_lnm_target" "$_lnm_source") || {
            _lnm_cleanup_rows "$_lnm_module" "$_lnm_ledger_file" "$_lnm_cli_path" "$_lnm_boot" || { rm -f "$_lnm_plan"; return 2; }
            rm -f "$_lnm_plan"
            return 1
        }
        if [ "$_lnm_verify" != exact ]; then
            _lnm_cleanup_rows "$_lnm_module" "$_lnm_ledger_file" "$_lnm_cli_path" "$_lnm_boot" || { rm -f "$_lnm_plan"; return 2; }
            rm -f "$_lnm_plan"
            return 1
        fi
    done < "$_lnm_plan"
    _lnm_set_ledger_state "$_lnm_ledger_file" committed || return 2
    rm -f "$_lnm_plan"
    _lnm_log "$_lnm_module" "transaction-committed rules=$(awk -F'|' 'NF==4 {n++} END{print n+0}' "$_lnm_ledger_file") generation=$_lnm_generation"
    return 0
)
