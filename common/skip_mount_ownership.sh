#!/system/bin/sh
# Ownership-safe helpers for the root-manager skip markers used by Ziyu.
set +e

_zso_module() { printf '%s\n' "${1:-${MODDIR:-${MODULE_DIR:-/data/adb/modules/Ziyu}}}"; }

ziyu_skip_migrate_legacy_ownership() {
    _zso_module_path=$(_zso_module "${1:-}")
    _zso_legacy="$_zso_module_path/config/self-mount-owned"
    # 这些标记位于**本模块自己的目录**里，只可能是字域（历史版本或当前挂载后端）
    # 自己写的，没有第三方会在别人的模块目录里创建 skip_mount。
    #
    # 真机踩到的坑：旧版自挂载时代留下的 skip_mount 没有归属标记，被判定成
    # "foreign-skip-mount" → post-fs-data 直接跳过整个挂载阶段 → 字体永远不生效，
    # 而且每次开机都会累积一次"启动证据不足"。所以这里把无主标记认领为字域所有，
    # 后续由挂载后端决定保留还是释放（`ziyu_skip_marker_release`）。
    if [ -e "$_zso_module_path/skip_mount" ] && [ ! -e "$_zso_module_path/.ziyu_skip_mount_owned" ]; then
        : > "$_zso_module_path/.ziyu_skip_mount_owned" 2>/dev/null || return 1
    fi
    if [ -e "$_zso_module_path/skip_mountify" ] && [ ! -e "$_zso_module_path/.ziyu_skip_mountify_owned" ]; then
        : > "$_zso_module_path/.ziyu_skip_mountify_owned" 2>/dev/null || return 1
    fi
    rm -f "$_zso_legacy" 2>/dev/null || return 1
}

ziyu_skip_marker_owned() {
    _zso_module_path=$(_zso_module "${1:-}")
    case "${2:-skip_mount}" in
        skip_mount) [ -f "$_zso_module_path/.ziyu_skip_mount_owned" ] ;;
        skip_mountify) [ -f "$_zso_module_path/.ziyu_skip_mountify_owned" ] ;;
        *) return 1 ;;
    esac
}

ziyu_foreign_skip_mount_present() {
    _zso_module_path=$(_zso_module "${1:-}")
    [ -e "$_zso_module_path/skip_mount" ] || return 1
    ziyu_skip_migrate_legacy_ownership "$_zso_module_path" || return 0
    ! ziyu_skip_marker_owned "$_zso_module_path" skip_mount
}

ziyu_skip_marker_claim() {
    _zso_module_path=$(_zso_module "${1:-}")
    _zso_marker="${2:-skip_mount}"
    case "$_zso_marker" in
        skip_mount) _zso_owner="$_zso_module_path/.ziyu_skip_mount_owned" ;;
        skip_mountify) _zso_owner="$_zso_module_path/.ziyu_skip_mountify_owned" ;;
        *) return 2 ;;
    esac
    ziyu_skip_migrate_legacy_ownership "$_zso_module_path" || return 1
    if [ -e "$_zso_module_path/$_zso_marker" ]; then
        [ -f "$_zso_owner" ] && return 0
        return 2
    fi
    : > "$_zso_module_path/$_zso_marker" 2>/dev/null || return 1
    : > "$_zso_owner" 2>/dev/null || {
        rm -f "$_zso_module_path/$_zso_marker" 2>/dev/null || true
        return 1
    }
    return 0
}

ziyu_skip_marker_release() {
    _zso_module_path=$(_zso_module "${1:-}")
    _zso_marker="${2:-skip_mount}"
    case "$_zso_marker" in
        skip_mount) _zso_owner="$_zso_module_path/.ziyu_skip_mount_owned" ;;
        skip_mountify) _zso_owner="$_zso_module_path/.ziyu_skip_mountify_owned" ;;
        *) return 2 ;;
    esac
    ziyu_skip_migrate_legacy_ownership "$_zso_module_path" || return 1
    [ -f "$_zso_owner" ] || return 0
    rm -f "$_zso_module_path/$_zso_marker" "$_zso_owner" 2>/dev/null
}

ziyu_skip_release_owned() {
    _zso_module_path=$(_zso_module "${1:-}")
    ziyu_skip_migrate_legacy_ownership "$_zso_module_path" || return 1
    ziyu_skip_marker_release "$_zso_module_path" skip_mount || return 1
    ziyu_skip_marker_release "$_zso_module_path" skip_mountify || return 1
    return 0
}
