#!/system/bin/sh
# Read-only details. Provider identity belongs to this boot, never redetect/switch it here.
_mbd_value() { ziyu_mount_conf_value "$1" "$2"; }
luoshu_mount_backend_details() {
    _mbd_module="${MODDIR:-/data/adb/modules/ziyu}"
    _mbd_file="$_mbd_module/config/mount-backend.conf"
    MOUNT_PROVIDER_NAME='尚未确认'
    MOUNT_PROVIDER_VERSION=''
    MOUNT_METHOD='尚未确认'
    MOUNT_METHOD_EVIDENCE='等待本次启动记录'
    MOUNT_ROOT_MANAGER='尚未确认'
    MOUNT_ROOT_VERSION=''
    if ! type ziyu_backend_state_load >/dev/null 2>&1; then
        . "$_mbd_module/common/mount_backend_state.sh" || return 1
    fi
    # Internal callers may reuse the snapshot they just loaded/committed.
    # Ordinary detail queries always read the current file again.
    if [ "${1:-}" != snapshot ] || [ "${ZIYU_BACKEND_STATE_FILE:-}" != "$_mbd_file" ] ||
       [ "${ZIYU_BACKEND_STATE_LOADED:-0}" != 1 ]; then
        ziyu_backend_state_load "$_mbd_file" || :
    fi
    _mbd_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')
    [ -n "$_mbd_boot" ] && [ "$ZIYU_BACKEND_BOOT_ID" = "$_mbd_boot" ] || return 0
    MOUNT_ROOT_MANAGER=$ZIYU_BACKEND_ROOT_MANAGER
    MOUNT_ROOT_VERSION=$ZIYU_BACKEND_ROOT_VERSION
    MOUNT_PROVIDER_VERSION=$ZIYU_BACKEND_META_VERSION
    _mbd_selected=$ZIYU_BACKEND_SELECTED_BACKEND
    _mbd_fallback=$ZIYU_BACKEND_FALLBACK_USED
    _mbd_fallback_reason=$ZIYU_BACKEND_FALLBACK_REASON
    _mbd_provider=$ZIYU_BACKEND_PROVIDER_ID
    [ -n "$_mbd_provider" ] || _mbd_provider=$ZIYU_BACKEND_META_ENGINE
    if [ "$_mbd_selected" = self ]; then
        MOUNT_PROVIDER_NAME='字域自挂载'
        if [ "$_mbd_fallback" = 1 ]; then
            MOUNT_PROVIDER_NAME="字域自挂载（回退：${_mbd_fallback_reason:-外部挂载不可用}）"
        fi
        MOUNT_PROVIDER_VERSION=''
    else
        case "$_mbd_provider" in
            nomount) MOUNT_PROVIDER_NAME='NoMount 元模块' ;;
            hybrid-mount) MOUNT_PROVIDER_NAME='Hybrid Mount 元模块' ;;
            mountify) MOUNT_PROVIDER_NAME='Mountify 元模块' ;;
            magic-mount) MOUNT_PROVIDER_NAME='Magic Mount 元模块' ;;
            meta-overlayfs) MOUNT_PROVIDER_NAME='Meta OverlayFS 元模块' ;;
            magisk-magic-mount) MOUNT_PROVIDER_NAME='Magisk 内置 Magic Mount'; MOUNT_PROVIDER_VERSION='' ;;
            apatch-native-module-mount) MOUNT_PROVIDER_NAME='APatch 内置模块挂载'; MOUNT_PROVIDER_VERSION='' ;;
            none) MOUNT_PROVIDER_NAME='无外部提供者' ;;
            *) MOUNT_PROVIDER_NAME="${_mbd_provider:-尚未确认}" ;;
        esac
    fi
    if [ "$_mbd_selected" = self ] &&
       [ "$(cat /data/adb/luoshu/self-mount/boot-id 2>/dev/null | tr -d '\r\n')" = "$_mbd_boot" ] &&
       [ "$(_mbd_value "$_mbd_module/config/self-mount.conf" state)" = mounted ]; then
        _mbd_self_method=$(awk -F'|' '
            $3=="overlay" {overlay=1} $3=="bind" {bind=1} $3=="mirror" {mirror=1}
            END {if(overlay) printf "OverlayFS"; if(bind) printf "%s文件 bind 挂载",overlay?" + ":""; if(mirror) printf "%s目录镜像 bind 挂载（独立字体别名）",(overlay||bind)?" + ":""}
        ' "$_mbd_module/config/self-mount-required.conf" 2>/dev/null)
        if [ -n "$_mbd_self_method" ]; then
            MOUNT_METHOD="$_mbd_self_method"
            MOUNT_METHOD_EVIDENCE='本次启动字域自挂载事务逐项记录'
            if [ "$_mbd_fallback" = 1 ]; then
                MOUNT_METHOD_EVIDENCE="$_mbd_fallback_reason；本次启动字域自挂载事务逐项记录"
            fi
            return 0
        fi
    fi
    # VFS redirection has no mountinfo entry. Use the selected provider's boot record.
    case "$_mbd_selected:$_mbd_provider" in
        external:nomount|meta:nomount)
            MOUNT_METHOD='NoMount VFS 路径重定向'; MOUNT_METHOD_EVIDENCE='本次启动提供者记录'; return 0 ;;
        external:hybrid-mount|meta:hybrid-mount)
            if [ "$ZIYU_BACKEND_META_HYBRID_MODE" = vfs ]; then
                MOUNT_METHOD='Hybrid Mount VFS 路径重定向'; MOUNT_METHOD_EVIDENCE='本次启动 Hybrid Mount 配置'; return 0
            fi ;;
    esac
    # Report observed filesystem operations without attributing arbitrary mounts to this module.
    _mbd_observed=$(awk '
        $5 ~ /^\/(system|vendor|product|system_ext|odm)(\/.*)?$/ &&
        ($5 ~ /\/fonts(\/|$)/ || $5 ~ /\/(fonts|font_fallback).*\.xml$/ || $5 ~ /^\/(system|vendor|product|system_ext|odm)$/) {
            for(i=7;i<=NF;i++) if($i=="-") {
                if($(i+1)=="overlay") overlay=1
                else if($(i+1)=="tmpfs") tmpfs=1
                else if($4!="/" && $5 ~ /(\.ttf|\.otf|\.ttc|\.xml)$/) bind=1
                break
            }
        }
        END {if(overlay) printf "OverlayFS"; if(tmpfs) printf "%sMagic Mount（tmpfs）",overlay?" + ":""; if(bind) printf "%s文件 bind 挂载",(overlay||tmpfs)?" + ":""}
    ' /proc/1/mountinfo 2>/dev/null)
    if [ -n "$_mbd_observed" ]; then
        MOUNT_METHOD="$_mbd_observed"
        MOUNT_METHOD_EVIDENCE='PID 1 字体路径挂载信息（可能包含其他模块）'
    else
        MOUNT_METHOD='未观察到可识别的挂载方式'
        MOUNT_METHOD_EVIDENCE='PID 1 字体路径挂载信息；不据名称推断已生效'
    fi
}
