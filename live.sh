#!/system/bin/sh
# 临时 Root 立即生效入口（保持在模块根目录，与 custom-font-switcher 的
# live.sh 位置一致）：实际逻辑在 common/ziyu_live.sh。
MODDIR="${0%/*}"
exec sh "$MODDIR/common/ziyu_live.sh" "$@"
