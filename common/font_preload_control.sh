#!/system/bin/sh
# Manage only the separately owned Ziyu preload companion; never enable Zygisk
# or alter the manager's denylist. Marker changes take effect after reboot.
set -eu
MODDIR="${MODDIR:-/data/adb/modules/ziyu}"
. "$MODDIR/common/font_preload_helper.sh"
target=/data/adb/modules/ziyu-font-preload
pending=/data/adb/modules_update/ziyu-font-preload
case "${1:-status}" in
  status)
    state=absent
    if [ -e "$target" ] || [ -L "$target" ]; then
      if ! ziyu_preload_helper_owned "$target"; then state=foreign
      elif [ -e "$target/remove" ] || [ -L "$target/remove" ]; then state=removed
      elif [ -e "$target/disable" ] || [ -L "$target/disable" ]; then state=disabled
      else state=enabled; fi
    fi
    callback=unobserved
    boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true)
    for file in "$MODDIR/config/font-preload-arm64-v8a.conf" "$MODDIR/config/font-preload-armeabi-v7a.conf" \
                "$MODDIR/config/font-preload-arm64-v8a-denylist.conf" "$MODDIR/config/font-preload-armeabi-v7a-denylist.conf"; do
      if [ -n "$boot" ] && [ -f "$file" ] && ! [ -L "$file" ] && grep -Fxq "boot_id=$boot" "$file" && grep -qx 'state=callback-observed' "$file"; then
        callback=observed
        break
      fi
    done
    printf 'state=%s\ncallback=%s\n' "$state" "$callback"
    ;;
  enable|disable)
    action=$1
    # Preflight all existing owned copies before making any changes.
    ziyu_preload_helper_owned "$target" || { echo '字域预加载组件未安装或不属于字域' >&2; exit 1; }
    for dir in "$target" "$pending"; do
      if [ -e "$dir" ] || [ -L "$dir" ]; then
        ziyu_preload_helper_owned "$dir" || { echo '预加载组件身份不匹配' >&2; exit 1; }
        [ ! -L "$dir/disable" ] && [ ! -L "$dir/remove" ] || { echo '预加载状态文件异常' >&2; exit 1; }
        [ ! -e "$dir/disable" ] || [ -f "$dir/disable" ] || { echo '预加载停用标记异常' >&2; exit 1; }
        if [ "$action" = enable ] && [ -e "$dir/remove" ]; then
          echo '组件已被标记移除，请在 Root 管理器中处理' >&2; exit 1
        fi
      fi
    done
    backup="$MODDIR/config/.preload-control.$$"
    mkdir "$backup" || { echo '无法准备预加载状态回滚' >&2; exit 1; }
    for entry in 'pending' 'target'; do
      eval "dir=\$$entry"
      if [ -f "$dir/disable" ]; then
        cp -p "$dir/disable" "$backup/$entry" || { rm -f "$backup/pending" "$backup/target"; rmdir "$backup" 2>/dev/null || true; exit 1; }
      fi
    done
    rollback() {
      for entry in 'pending' 'target'; do
        eval "dir=\$$entry"
        [ -d "$dir" ] || continue
        if [ -f "$backup/$entry" ]; then cp -p "$backup/$entry" "$dir/disable" || true
        else rm -f "$dir/disable" || true; fi
      done
    }
    cleanup() { rm -f "$backup/pending" "$backup/target"; rmdir "$backup" 2>/dev/null || true; }
    trap 'rollback; cleanup; exit 130' HUP INT TERM
    # Mark the pending copy first so an already staged upgrade cannot undo the choice.
    for dir in "$pending" "$target"; do
      [ -d "$dir" ] || continue
      if [ "$action" = disable ]; then
        if ! : > "$dir/disable"; then rollback; cleanup; exit 1; fi
      else
        if ! rm -f "$dir/disable"; then rollback; cleanup; exit 1; fi
      fi
    done
    trap - HUP INT TERM
    cleanup
    printf 'state=%s\nrebootRequired=true\n' "$([ "$action" = enable ] && echo enabled || echo disabled)"
    ;;
  *) echo '未知预加载操作' >&2; exit 64 ;;
esac
