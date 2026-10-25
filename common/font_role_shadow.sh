#!/system/bin/sh
# LuoShu Phase 2 role classifier + shadow replacement planner.
# Diagnostic only: never changes fonts, XML, mounts or /data/fonts.
set +e

MODDIR="${MODDIR:-${MODULE_DIR:-/data/adb/modules/ziyu}}"
MODULE_DIR="$MODDIR"
CONFIG_DIR="$MODDIR/config"
PYROOT="$MODDIR/common/python"
PYBIN="$PYROOT/bin/luoshu-python"
SCRIPT="$MODDIR/common/font_role_shadow.py"
TOPOLOGY="$CONFIG_DIR/device_font_topology.json"
ROLES="$CONFIG_DIR/device_font_roles.json"
PLAN="$CONFIG_DIR/device_font_shadow_plan.json"
LOG="$MODDIR/logs/font-role-shadow.log"

_role_shadow_exec() {
    if [ -n "${LUOSHU_PYTHON:-}" ]; then
        "$LUOSHU_PYTHON" "$@"
        return $?
    fi
    [ -x "$PYBIN" ] || return 127
    PYTHONHOME="$PYROOT" \
    PYTHONPATH="$MODDIR/common:$PYROOT/lib/python3.14:$PYROOT/lib/python3.14/site-packages" \
    LD_LIBRARY_PATH="$PYROOT/lib:$PYROOT/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$PYBIN" "$@"
}

_role_shadow_refresh() {
    mkdir -p "$CONFIG_DIR" "$MODDIR/logs" 2>/dev/null || true
    rm -rf "$CONFIG_DIR/universal-font-plans" 2>/dev/null || true
    [ -f "$SCRIPT" ] || {
        printf '{"status":"error","message":"缺少字体角色分类组件"}\n'
        return 1
    }
    [ -s "$TOPOLOGY" ] || {
        printf '{"status":"error","message":"设备字体拓扑尚未就绪"}\n'
        return 1
    }
    _role_shadow_exec "$SCRIPT" \
        --topology "$TOPOLOGY" \
        --roles-output "$ROLES" \
        --plan-output "$PLAN" \
        --slot-groups "$CONFIG_DIR/coloros_slot_groups.conf"
}

case "${1:-refresh}" in
    refresh|build)
        _role_shadow_refresh
        ;;
    validate|status)
        _role_shadow_exec "$SCRIPT" --validate \
            --topology "$TOPOLOGY" \
            --roles-output "$ROLES" \
            --plan-output "$PLAN" \
            --slot-groups "$CONFIG_DIR/coloros_slot_groups.conf"
        ;;
    roles-path)
        printf '%s\n' "$ROLES"
        ;;
    plan-path)
        printf '%s\n' "$PLAN"
        ;;
    *)
        echo "Usage: $0 {refresh|validate|status|roles-path|plan-path}" >&2
        exit 2
        ;;
esac
