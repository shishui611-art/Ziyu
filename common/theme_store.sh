#!/system/bin/sh
# 主题商店字体替换（把当前字体写进 /data 上的主题字体文件，含备份与还原）
#
# 移植自 custom-font-switcher v1.5.3 的 mount.sh datafont_swap / datafont_restore 思路。
#
# 为什么需要（真机结论，见对方注释）：
#   厂商主题服务（ColorOS 主题、魅族 customizecenter、vivo bbkcore、MIUI 主题）
#   把「设置 → 字体样式 / 主题商店里选的字体」单独存一份在 /data 上，优先级高于
#   /system/fonts。只换系统字体时这些 App/界面仍显示旧字体。
#   而这些服务靠「文件 inode / 修改时间有没有变」判断要不要重读 ——
#   纯 bind mount 只换内容、不改 inode，它们看不出来，所以必须**真的写文件**。
#
# 行为：
#   · 首次替换前把原件备份到 .ziyu-state/theme-backup/（只在第一次备份，之后不覆盖）
#   · 用「同目录临时文件 + mv」改名覆盖，换掉 inode 与 mtime，让主题服务重读
#   · restore 把备份原件还回去（切回默认字体或关闭开关时使用）
#
# 风险（如实告知）：
#   · 会覆盖 /data 上的主题字体文件（有备份，可还原）
#   · 对方真机记录过「复制后任何 WebUI 闪退」，因此**默认关闭**，只在需要时手动打开
#
# 用法：theme_store.sh status|apply|restore
set +e
MODDIR="${MODDIR:-$(CDPATH= cd -- "${0%/*}/.." 2>/dev/null && pwd)}"
LOG_FILE="$MODDIR/logs/theme-store.log"
STATE="$MODDIR/.ziyu-state/theme-backup"
MANIFEST="$STATE/manifest.txt"
STATE_CONF="$MODDIR/.ziyu-state/theme-store.state"

_ts_json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\r\n' '  '; }
_ts_log() {
    mkdir -p "$MODDIR/logs" 2>/dev/null
    printf '[%s] [THEME-STORE] %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$1" >> "$LOG_FILE" 2>/dev/null
}
_ts_err() { printf '{"status":"error","message":"%s"}\n' "$(_ts_json_escape "$1")"; exit 1; }

# 目标 ROM（与 slot_coverage.sh 同口径）
_ts_rom() {
    _r=''
    [ -f "$MODDIR/config/slot_coverage_rom.conf" ] && \
        _r=$(sed -n 's/^rom=//p' "$MODDIR/config/slot_coverage_rom.conf" 2>/dev/null | head -n1 | tr -d ' \r')
    if [ -z "$_r" ]; then
        case "$(getprop ro.build.version.oplusrom 2>/dev/null)$(getprop ro.build.version.opporom 2>/dev/null)" in
            '') ;; *) _r=coloros ;;
        esac
    fi
    if [ -z "$_r" ]; then
        case "$(getprop ro.miui.ui.version.name 2>/dev/null)" in
            '') ;; *) _r=hyperos ;;
        esac
    fi
    if [ -z "$_r" ]; then
        case "$(getprop ro.vivo.os.version 2>/dev/null)$(getprop ro.vivo.os.build.display.id 2>/dev/null)" in
            '') ;; *) _r=originos ;;
        esac
    fi
    if [ -z "$_r" ]; then
        case "$(getprop ro.build.display.id 2>/dev/null)" in
            *Flyme*|*flyme*) _r=flyme ;;
        esac
    fi
    [ -n "$_r" ] || _r=coloros
    printf '%s' "$_r"
}

# 本 ROM 的主题字体目录（只列真实存在的）
_ts_dirs() {
    case "$(_ts_rom)" in
        coloros)  _c="/data/format_unclear/font /data/oplus/uxres/theme /data/system/theme/fonts" ;;
        flyme)    _c="/data/customizecenter/font /data/theme/fonts /data/themes" ;;
        originos) _c="/data/bbkcore/theme /data/skin/fonts /data/system/theme/fonts" ;;
        hyperos)  _c="/data/system/theme/fonts" ;;
        *)        _c="/data/system/theme/fonts" ;;
    esac
    for _d in $_c; do
        [ -d "$_d" ] && printf '%s\n' "$_d"
    done
}

# 当前字体源文件（优先已发布的 regular 负载）
_ts_source() {
    for _s in "$MODDIR/system/fonts/SysFont-Regular.ttf" \
              "$MODDIR/system/fonts/.ziyu-font-store/regular.font"; do
        [ -s "$_s" ] && { printf '%s' "$_s"; return 0; }
    done
    for _s in "$MODDIR/system/fonts"/*.ttf; do
        [ -s "$_s" ] && { printf '%s' "$_s"; return 0; }
    done
    return 1
}

_ts_bak_name() {
    printf '%s' "$1" | sed 's#^/##; s#/#_#g'
}

_ts_file_sig() { stat -c '%s:%Y' "$1" 2>/dev/null; }

# 单文件替换：备份 + 改名覆盖
_ts_swap() {
    _src="$1"; _dst="$2"
    [ -f "$_src" ] && [ -f "$_dst" ] || return 1
    # 已经是我们这份（大小+源 mtime 一致）→ 跳过，避免每次重复写盘
    _key=$(_ts_bak_name "$_dst")
    _cur="$(_ts_file_sig "$_dst"):$(_ts_file_sig "$_src")"
    _old=$(sed -n "s|^$_key=||p" "$STATE_CONF" 2>/dev/null | tail -n1)
    [ -n "$_old" ] && [ "$_old" = "$_cur" ] && return 0

    mkdir -p "$STATE" 2>/dev/null || return 1
    _bak="$STATE/$_key"
    # 只在第一次备份：之后目标已经是我们替换过的内容，不能再当原件备份
    if [ ! -s "$_bak" ]; then
        cp -f "$_dst" "$_bak" 2>/dev/null || return 1
        [ -s "$_bak" ] || return 1
        printf '%s\n' "$_dst" >> "$MANIFEST" 2>/dev/null
        _ts_log "backup $_dst -> $_bak"
    fi
    # 同目录临时文件 → mv 覆盖（换 inode 与 mtime）
    _dir=${_dst%/*}
    _tmp="$_dir/.ziyu_ts_$$"
    cp -f "$_src" "$_tmp" 2>/dev/null || { rm -f "$_tmp" 2>/dev/null; return 1; }
    [ -s "$_tmp" ] || { rm -f "$_tmp" 2>/dev/null; return 1; }
    # 写入完整性：大小必须与源一致
    [ "$(stat -c %s "$_tmp" 2>/dev/null)" = "$(stat -c %s "$_src" 2>/dev/null)" ] || {
        rm -f "$_tmp" 2>/dev/null; return 1; }
    chmod 0644 "$_tmp" 2>/dev/null
    chcon u:object_r:system_file:s0 "$_tmp" 2>/dev/null
    if mv -f "$_tmp" "$_dst" 2>/dev/null; then
        chmod 0644 "$_dst" 2>/dev/null
        chcon u:object_r:system_file:s0 "$_dst" 2>/dev/null
        { sed -i "/^$_key=/d" "$STATE_CONF" 2>/dev/null
          printf '%s=%s\n' "$_key" "$(_ts_file_sig "$_dst"):$(_ts_file_sig "$_src")"
        } >> "$STATE_CONF" 2>/dev/null
        _ts_log "replaced $_dst"
        return 0
    fi
    rm -f "$_tmp" 2>/dev/null
    return 1
}

_ts_do_apply() {
    _src=$(_ts_source) || _ts_err '没有可用的字体负载，请先应用一次字体。'
    _dirs=$(_ts_dirs)
    [ -n "$_dirs" ] || _ts_err '本机没有找到可替换的主题字体目录。'
    _ok=0; _skip=0; _fail=0
    for _d in $_dirs; do
        for _f in "$_d"/*.ttf "$_d"/*.otf "$_d"/*.ttc "$_d"/*.TTF "$_d"/*.OTF; do
            [ -f "$_f" ] || continue
            if _ts_swap "$_src" "$_f"; then _ok=$((_ok+1)); else _fail=$((_fail+1)); fi
        done
    done
    printf '{"status":"ok","data":{"replaced":%s,"failed":%s,"source":"%s","rom":"%s"}}\n' \
        "$_ok" "$_fail" "$(_ts_json_escape "$_src")" "$(_ts_json_escape "$(_ts_rom)")"
}

_ts_do_restore() {
    [ -s "$MANIFEST" ] || { printf '{"status":"ok","data":{"restored":0,"message":"没有需要还原的主题字体。"}}\n'; exit 0; }
    _n=0; _left=""
    while IFS= read -r _dst || [ -n "$_dst" ]; do
        [ -n "$_dst" ] || continue
        case "$_dst" in /data/*) ;; *) continue ;; esac
        _bak="$STATE/$(_ts_bak_name "$_dst")"
        [ -s "$_bak" ] || { _left="$_left$_dst
"; continue; }
        _dir=${_dst%/*}
        [ -d "$_dir" ] || { _left="$_left$_dst
"; continue; }
        _tmp="$_dir/.ziyu_rs_$$"
        if cp -f "$_bak" "$_tmp" 2>/dev/null; then
            chmod 0644 "$_tmp" 2>/dev/null
            chcon u:object_r:system_file:s0 "$_tmp" 2>/dev/null
            if mv -f "$_tmp" "$_dst" 2>/dev/null; then
                _n=$((_n+1)); _ts_log "restored $_dst"
            else
                rm -f "$_tmp" 2>/dev/null; _left="$_left$_dst
"
            fi
        else
            _left="$_left$_dst
"
        fi
    done < "$MANIFEST"
    printf '%s' "$_left" > "$MANIFEST.tmp" 2>/dev/null && mv -f "$MANIFEST.tmp" "$MANIFEST" 2>/dev/null
    rm -f "$STATE_CONF" 2>/dev/null
    printf '{"status":"ok","data":{"restored":%s}}\n' "$_n"
}

case "${1:-status}" in
    status)
        _rom=$(_ts_rom)
        _n_bak=0
        [ -s "$MANIFEST" ] && _n_bak=$(grep -c . "$MANIFEST" 2>/dev/null)
        _dirs=$(_ts_dirs)
        _n_files=0
        for _d in $_dirs; do
            for _f in "$_d"/*.ttf "$_d"/*.otf; do
                [ -f "$_f" ] && _n_files=$((_n_files+1))
            done
        done
        printf '{"status":"ok","data":{"rom":"%s","dirs":"%s","theme_files":%s,"backed_up":%s}}\n' \
            "$(_ts_json_escape "$_rom")" "$(_ts_json_escape "$(printf '%s' "$_dirs" | tr '\n' ' ')")" \
            "$_n_files" "${_n_bak:-0}"
        ;;
    apply)   _ts_do_apply ;;
    restore) _ts_do_restore ;;
    *) _ts_err '用法：theme_store.sh [status|apply|restore]' ;;
esac
