#!/system/bin/sh
# 槽位覆盖扩展：把已选字体扩展到目标 ROM 的完整字体槽清单。
#
# 槽位清单数据来源：custom-font-switcher v1.5.1.3（作者 暗影双生，
# https://github.com/ayss2188/custom-font-switcher）的 targets/*.list，
# 仅使用其中的字体文件名清单作为设备槽位事实数据；本文件的脚本逻辑为字域
# 独立实现，未复制对方脚本代码。详见 THIRD_PARTY_NOTICES.md。
set +e
_ZS_CR=$(printf '\r')

# 纯 shell 去首尾空格/回车，避免每个槽位行都开 sed/tr 进程。
# 结果放 _ziyu_slot_trimmed，调用方直接用变量，不再套命令替换。
_ziyu_slot_trim() {
    _zst_v="$1"
    while :; do
        case "$_zst_v" in
            ' '*|"$_ZS_CR"*) _zst_v=${_zst_v#?} ;;
            *) break ;;
        esac
    done
    while :; do
        case "$_zst_v" in
            *' '|*"$_ZS_CR") _zst_v=${_zst_v%?} ;;
            *) break ;;
        esac
    done
    _ziyu_slot_trimmed="$_zst_v"
}

_ziyu_slot_module() {
    _zsm_mod="$1"
    [ -n "$_zsm_mod" ] || _zsm_mod="${MODDIR:-/data/adb/modules/ziyu}"
    printf '%s' "$_zsm_mod"
}

# 目标 ROM：优先读配置，其次按系统属性猜测，最后回退 coloros。
ziyu_slot_rom() {
    _zsr_mod=$(_ziyu_slot_module "${1:-}")
    _zsr_rom=''
    if [ -f "$_zsr_mod/config/slot_coverage_rom.conf" ]; then
        _zsr_rom=$(sed -n 's/^rom=//p' "$_zsr_mod/config/slot_coverage_rom.conf" 2>/dev/null | head -n1 | tr -d ' \r')
    fi
    if [ -z "$_zsr_rom" ]; then
        case "$(getprop ro.build.version.oplusrom 2>/dev/null)$(getprop ro.build.version.opporom 2>/dev/null)" in
            '') ;;
            *) _zsr_rom=coloros ;;
        esac
    fi
    if [ -z "$_zsr_rom" ]; then
        case "$(getprop ro.miui.ui.version.name 2>/dev/null)" in
            '') ;;
            *) _zsr_rom=hyperos ;;
        esac
    fi
    if [ -z "$_zsr_rom" ]; then
        case "$(getprop ro.vivo.os.version 2>/dev/null)$(getprop ro.vivo.os.build.display.id 2>/dev/null)" in
            '') ;;
            *) _zsr_rom=originos ;;
        esac
    fi
    if [ -z "$_zsr_rom" ]; then
        case "$(getprop ro.build.display.id 2>/dev/null)" in
            *Flyme*|*flyme*) _zsr_rom=flyme ;;
        esac
    fi
    [ -n "$_zsr_rom" ] || _zsr_rom=coloros
    printf '%s' "$_zsr_rom"
}

ziyu_slot_list_file() {
    _zsl_mod=$(_ziyu_slot_module "${1:-}")
    _zsl_rom="${2:-$(_ziyu_slot_rom_fn "$_zsl_mod")}"
    _zsl_file="$_zsl_mod/common/slot_targets/$_zsl_rom.list"
    [ -s "$_zsl_file" ] || _zsl_file="$_zsl_mod/common/slot_targets/coloros.list"
    [ -s "$_zsl_file" ] || return 1
    printf '%s' "$_zsl_file"
}

# 兼容旧函数名（避免调用点重复夹带参数）。
_ziyu_slot_rom_fn() { ziyu_slot_rom "$1"; }

ziyu_slot_include_lang() {
    _zsil_mod=$(_ziyu_slot_module "${1:-}")
    _zsil_value=''
    if [ -f "$_zsil_mod/config/tools/slot-coverage-lang.conf" ]; then
        _zsil_value=$(sed -n 's/^enabled=//p' "$_zsil_mod/config/tools/slot-coverage-lang.conf" 2>/dev/null | head -n1 | tr -d ' \r')
    fi
    [ "$_zsil_value" = 1 ]
}

# 设备槽位缓存：解析 fonts.xml 家族 + 机型清单得到的槽位表。
# 缓存文件由 device_slot_discovery.py 生成，fonts.xml/清单更新后自动重建。
ziyu_slot_device_cache() {
    _zsdc_mod=$(_ziyu_slot_module "${1:-}")
    printf '%s/config/device-slots.list' "$_zsdc_mod"
}

ziyu_slot_discover() (
    _zsdi_mod=$(_ziyu_slot_module "${1:-}")
    _zsdi_out="$(ziyu_slot_device_cache "$_zsdi_mod")"
    # ★ 全覆盖纯 shell 探测优先（移植自 custom-font-switcher 的槽位探测，classifier=3）；
    #   失败/缺失再回退 Python 保守版（device_slot_discovery.py，classifier=2）。
    _zsdi_cfs="$_zsdi_mod/common/slot_discovery_cfs.sh"
    if [ -f "$_zsdi_cfs" ]; then
        . "$_zsdi_cfs"
        ziyu_cfs_discover "$_zsdi_mod" && return 0
    fi
    _zsdi_py="$_zsdi_mod/common/device_slot_discovery.py"
    _zsdi_python="$_zsdi_mod/common/python/bin/ziyu-python"
    if [ ! -x "$_zsdi_python" ] || [ ! -f "$_zsdi_py" ]; then
        return 1
    fi
    _zsdi_stale=1
    if [ -s "$_zsdi_out" ]; then
        _zsdi_stale=0
        # 分类规则或模块版本变化时强制重建缓存，避免沿用旧口径。
        _zsdi_cls=''; _zsdi_modv=''
        while IFS= read -r _zsdi_hdr || [ -n "$_zsdi_hdr" ]; do
            case "$_zsdi_hdr" in
                classifier=*) _zsdi_cls=${_zsdi_hdr#classifier=} ;;
                module_version=*) _zsdi_modv=${_zsdi_hdr#module_version=} ;;
                *) break ;;
            esac
        done < "$_zsdi_out"
        [ "$_zsdi_cls" = 2 ] || _zsdi_stale=1
        _zsdi_now=''
        IFS= read -r _zsdi_now < "$_zsdi_mod/module.prop" 2>/dev/null || _zsdi_now=''
        _zsdi_now=$(sed -n 's/^versionCode=//p' "$_zsdi_mod/module.prop" 2>/dev/null | head -n1 | tr -d ' \r')
        [ -n "$_zsdi_modv" ] && [ "$_zsdi_modv" = "$_zsdi_now" ] || _zsdi_stale=1
        for _zsdi_x in /system/etc/fonts.xml /system_ext/etc/fonts.xml /product/etc/fonts.xml /vendor/etc/fonts.xml; do
            [ -f "$_zsdi_x" ] || continue
            [ "$_zsdi_x" -nt "$_zsdi_out" ] && { _zsdi_stale=1; break; }
        done
        [ -n "$ZIYU_SLOT_DISCOVERY_FORCE" ] && _zsdi_stale=1
    fi
    [ "$_zsdi_stale" = 1 ] || return 0
    _zsdi_targets="$_zsdi_mod/common/slot_targets/$(ziyu_slot_rom "$_zsdi_mod").list"
    [ -s "$_zsdi_targets" ] || _zsdi_targets="$_zsdi_mod/common/slot_targets/coloros.list"
    _zsdi_pyroot="$_zsdi_mod/common/python"
    mkdir -p "$_zsdi_mod/logs" "$_zsdi_mod/config" 2>/dev/null
    (
        export PYTHONHOME="$_zsdi_pyroot"
        export PYTHONPATH="$_zsdi_mod/common:$_zsdi_pyroot/lib/python3.14:$_zsdi_pyroot/lib/python3.14/site-packages"
        export LD_LIBRARY_PATH="$_zsdi_pyroot/lib:$_zsdi_pyroot/lib/python3.14/lib-dynload${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
        exec "$_zsdi_python" "$_zsdi_py" "$_zsdi_mod" "$_zsdi_out" "$_zsdi_targets"
    ) >> "$_zsdi_mod/logs/slot-coverage.log" 2>&1
)

# 从已发布/负载字体目录里挑出各字重文件，结果放在 _zcp_* 变量里。
ziyu_slot_collect_sources() {
    _zcp_dir="$1"
    _zcp_base=''; _zcp_bold=''; _zcp_semibold=''; _zcp_medium=''
    _zcp_light=''; _zcp_thin=''; _zcp_heavy=''; _zcp_vf=''
    for _zcp_f in "$_zcp_dir"/*.ttf "$_zcp_dir"/*.otf "$_zcp_dir"/*.ttc; do
        [ -f "$_zcp_f" ] || continue
        _zcp_name=${_zcp_f##*/}
        case "$_zcp_name" in
            *SemiBold*|*Semibold*|*semi-bold*|*Semi*Bold*) [ -n "$_zcp_semibold" ] || _zcp_semibold=$_zcp_f ;;
            *ExtraBold*|*Black*|*Heavy*) [ -n "$_zcp_heavy" ] || _zcp_heavy=$_zcp_f ;;
            *Bold*) [ -n "$_zcp_bold" ] || _zcp_bold=$_zcp_f ;;
            *Medium*) [ -n "$_zcp_medium" ] || _zcp_medium=$_zcp_f ;;
            *Light*) [ -n "$_zcp_light" ] || _zcp_light=$_zcp_f ;;
            *Thin*) [ -n "$_zcp_thin" ] || _zcp_thin=$_zcp_f ;;
        esac
        case "$_zcp_name" in
            *VF*|*Variable*|*variable*|*\[*\]*) [ -n "$_zcp_vf" ] || _zcp_vf=$_zcp_f ;;
        esac
        case "$_zcp_name" in
            *Regular*) [ -n "$_zcp_base" ] || _zcp_base=$_zcp_f ;;
        esac
    done
    [ -n "$_zcp_base" ] || {
        for _zcp_f in "$_zcp_dir"/*.ttf "$_zcp_dir"/*.otf "$_zcp_dir"/*.ttc; do
            [ -f "$_zcp_f" ] || continue
            _zcp_base=$_zcp_f
            break
        done
    }
    [ -n "$_zcp_base" ] || return 1
    return 0
}

# 给槽位文件名挑源文件：先按字重，其次可变字体，最后回退 Regular。
ziyu_slot_pick_source() {
    _zps_slot="$1"
    _zps_src=''
    case "$_zps_slot" in
        *Thin*) [ -n "$_zcp_thin" ] && _zps_src=$_zcp_thin ;;
        *Light*) [ -n "$_zcp_light" ] && _zps_src=$_zcp_light ;;
        *ExtraBold*|*Black*|*Heavy*) [ -n "$_zcp_heavy" ] && _zps_src=$_zcp_heavy ;;
        *SemiBold*|*Semibold*) [ -n "$_zcp_semibold" ] && _zps_src=$_zcp_semibold ;;
        *Medium*) [ -n "$_zcp_medium" ] && _zps_src=$_zcp_medium ;;
        *Bold*) [ -n "$_zcp_bold" ] && _zps_src=$_zcp_bold ;;
        *VF*|*Variable*|*\[*\]*)
            [ -n "$_zcp_vf" ] && _zps_src=$_zcp_vf
            [ -n "$_zps_src" ] || { [ -n "$_zcp_base" ] && _zps_src=$_zcp_base; }
            ;;
    esac
    [ -n "$_zps_src" ] || _zps_src=$_zcp_base
    [ -n "$_zps_src" ] || return 1
    printf '%s' "$_zps_src"
}

# 输出覆盖计划：每行 "源文件|目标槽位名"。
# 只计划设备上真实存在的 /system/fonts/<槽位>，避免给不存在的路径加规则。
ziyu_slot_classify() {
    case "$1" in
        *Digit*|*digit*|*Clock*|*clock*|*DIN*|*Number*|*number*|*Num*|*num*) printf 'digit' ;;
        *CJK*|*cjk*|*Hans*|*Hant*|*Hei*|*Song*|*Kai*|*Fang*|*MiSans*|*Harmony*|*SourceHan*|*SysFont*|*SysSans*|*OPPOSans*) printf 'cjk' ;;
        *) printf 'latin' ;;
    esac
}

# 从槽位覆盖配置读统计；没有配置时按完整计划现算。
ziyu_slot_stats() {
    _zss_mod=$(_ziyu_slot_module "${1:-}")
    # 设备槽位发现优先：这是“槽位总数/中文/数字”的设备口径。
    _zss_cache=$(ziyu_slot_device_cache "$_zss_mod")
    [ -s "$_zss_cache" ] || ziyu_slot_discover "$_zss_mod" >/dev/null 2>&1
    if [ -s "$_zss_cache" ]; then
        _zss_total=0; _zss_cjk=0; _zss_digit=0; _zss_latin=0
        while IFS= read -r _zss_line || [ -n "$_zss_line" ]; do
            case "$_zss_line" in
                count_total=*) _zss_total=${_zss_line#*=} ;;
                count_cjk=*) _zss_cjk=${_zss_line#*=} ;;
                count_digit=*) _zss_digit=${_zss_line#*=} ;;
                count_latin=*) _zss_latin=${_zss_line#*=} ;;
            esac
        done < "$_zss_cache"
        _zss_total=$((_zss_cjk + _zss_digit + _zss_latin))
        printf 'schema=ziyu-slot-coverage-v1\ntotal=%s\ncjk=%s\ndigit=%s\nlatin=%s\nrom=%s\n' \
            "$_zss_total" "${_zss_cjk:-0}" "${_zss_digit:-0}" "${_zss_latin:-0}" "$(ziyu_slot_rom "$_zss_mod")"
        return 0
    fi
    _zss_conf="$_zss_mod/config/slot-coverage.conf"
    if [ -s "$_zss_conf" ]; then
        _zss_rom=''; _zss_total=0; _zss_cjk=0; _zss_digit=0; _zss_latin=0
        while IFS= read -r _zss_line || [ -n "$_zss_line" ]; do
            case "$_zss_line" in
                rom=*) _zss_rom=${_zss_line#*=} ;;
                total=*) _zss_total=${_zss_line#*=} ;;
                cjk=*) _zss_cjk=${_zss_line#*=} ;;
                digit=*) _zss_digit=${_zss_line#*=} ;;
                latin=*) _zss_latin=${_zss_line#*=} ;;
            esac
        done < "$_zss_conf"
        printf 'schema=ziyu-slot-coverage-v1\ntotal=%s\ncjk=%s\ndigit=%s\nlatin=%s\nrom=%s\n' \
            "${_zss_total:-0}" "${_zss_cjk:-0}" "${_zss_digit:-0}" "${_zss_latin:-0}" "${_zss_rom:-unknown}"
        return 0
    fi
    _zss_fonts=''
    for _zss_dir in "$_zss_mod/.ziyu-payload/system/fonts" "$_zss_mod/system/fonts"; do
        [ -d "$_zss_dir" ] || continue
        _zss_fonts=$_zss_dir
        break
    done
    _zss_total=0; _zss_cjk=0; _zss_digit=0; _zss_latin=0
    if [ -n "$_zss_fonts" ]; then
        while IFS='|' read -r _zss_src _zss_slot; do
            [ -n "$_zss_slot" ] || continue
            _zss_total=$((_zss_total + 1))
            case "$(ziyu_slot_classify "$_zss_slot")" in
                cjk) _zss_cjk=$((_zss_cjk + 1)) ;;
                digit) _zss_digit=$((_zss_digit + 1)) ;;
                *) _zss_latin=$((_zss_latin + 1)) ;;
            esac
        done <<EOF
$(ziyu_slot_coverage_plan "$_zss_mod" "$_zss_fonts" "${ZIYU_SLOT_TARGET_ROOT:-/system/fonts}" 2>/dev/null)
EOF
    fi
    printf 'schema=ziyu-slot-coverage-v1\ntotal=%s\ncjk=%s\ndigit=%s\nlatin=%s\nrom=%s\n' \
        "$_zss_total" "$_zss_cjk" "$_zss_digit" "$_zss_latin" "$(ziyu_slot_rom "$_zss_mod")"
}

ziyu_slot_coverage_plan() {
    _zscp_mod=$(_ziyu_slot_module "${1:-}")
    _zscp_fonts="${2:-}"
    [ -d "$_zscp_fonts" ] || return 1
    _zscp_include_lang=0
    ziyu_slot_include_lang "$_zscp_mod" && _zscp_include_lang=1
    ziyu_slot_collect_sources "$_zscp_fonts" || return 1
    # 优先用设备槽位发现结果（fonts.xml 家族 + 机型清单），覆盖更接近对方模块的口径。
    ziyu_slot_discover "$_zscp_mod" >/dev/null 2>&1 || true
    _zscp_cache=$(ziyu_slot_device_cache "$_zscp_mod")
    if [ -s "$_zscp_cache" ]; then
        _zscp_target_root="${3:-${ZIYU_SLOT_TARGET_ROOT:-/system/fonts}}"
        while IFS= read -r _zscp_line || [ -n "$_zscp_line" ]; do
            case "$_zscp_line" in slot=*) ;; *) continue ;; esac
            # Android mksh 对 ${var%%|*} 的竖线模式解析不可靠，统一用 IFS 拆字段。
            _zscp_entry=${_zscp_line#slot=}
            IFS='|' read -r _zscp_name _zscp_cat _zscp_weight _zscp_family <<EOF
$_zscp_entry
EOF
            [ -n "$_zscp_name" ] || continue
            [ "$_zscp_cat" = lang ] && [ "$_zscp_include_lang" != 1 ] && continue
            case "$_zscp_name" in */*|*..*|*[!A-Za-z0-9._+-]*) continue ;; esac
            # 安全槽位过滤：TTC 合集、Emoji/彩色/时钟等特殊字形槽位不扩展。
            case "$_zscp_name" in *.ttc|*.TTC) continue ;; esac
            _zscp_lname=$(printf '%s' "$_zscp_name" | tr 'A-Z' 'a-z')
            case "$_zscp_lname" in *emoji*|*color*|*clock*) continue ;; esac
            [ -e "$_zscp_target_root/$_zscp_name" ] || continue
            _zscp_src=''
            case "$_zscp_weight" in
                heavy) [ -n "$_zcp_heavy" ] && _zscp_src=$_zcp_heavy ;;
                semibold) [ -n "$_zcp_semibold" ] && _zscp_src=$_zcp_semibold ;;
                bold) [ -n "$_zcp_bold" ] && _zscp_src=$_zcp_bold ;;
                medium) [ -n "$_zcp_medium" ] && _zscp_src=$_zcp_medium ;;
                light) [ -n "$_zcp_light" ] && _zscp_src=$_zcp_light ;;
                thin) [ -n "$_zcp_thin" ] && _zscp_src=$_zcp_thin ;;
            esac
            [ -n "$_zscp_src" ] || _zscp_src=$_zcp_base
            [ -n "$_zscp_src" ] || continue
            printf '%s|%s\n' "$_zscp_src" "$_zscp_name"
        done < "$_zscp_cache"
        return 0
    fi
    _zscp_list=$(ziyu_slot_list_file "$_zscp_mod") || return 1
    _zscp_section='main'
    _zscp_planned=0
    _zscp_target_root="${3:-${ZIYU_SLOT_TARGET_ROOT:-/system/fonts}}"
    while IFS= read -r _zscp_line || [ -n "$_zscp_line" ]; do
        _ziyu_slot_trim "$_zscp_line"
        _zscp_line="$_ziyu_slot_trimmed"
        case "$_zscp_line" in
            ''|\#*) continue ;;
            '['*']')
                _zscp_section=${_zscp_line#\[}
                _zscp_section=${_zscp_section%\]}
                continue
                ;;
        esac
        case "$_zscp_line" in
            */*|*..*|*[!A-Za-z0-9._+-]*) continue ;;
        esac
        [ "$_zscp_section" = lang ] && [ "$_zscp_include_lang" != 1 ] && continue
        case "$_zscp_line" in *.ttc|*.TTC) continue ;; esac
        _zscp_lname=$(printf '%s' "$_zscp_line" | tr 'A-Z' 'a-z')
        case "$_zscp_lname" in *emoji*|*color*|*clock*) continue ;; esac
        [ -e "$_zscp_target_root/$_zscp_line" ] || continue
        _zscp_src=$(ziyu_slot_pick_source "$_zscp_line") || continue
        printf '%s|%s\n' "$_zscp_src" "$_zscp_line"
        _zscp_planned=$((_zscp_planned + 1))
    done < "$_zscp_list"
    return 0
}

# 发布阶段把覆盖计划落到 staging 字体树：缺哪个槽位就用硬链接补上。
# 槽位覆盖扩展总开关：默认开启安全槽位扩展（跳过 TTC/Emoji/彩色/时钟/其它语种），
# 显式 enabled=0 可整体关闭。
ziyu_slot_coverage_enabled() {
    _zsce_mod=$(_ziyu_slot_module "${1:-}")
    _zsce_conf="$_zsce_mod/config/tools/slot-coverage-enabled.conf"
    [ -s "$_zsce_conf" ] || _zsce_conf="$_zsce_mod/config/slot-coverage-enabled.conf"
    # 默认开启；只有显式 enabled=0 才关闭。
    [ -s "$_zsce_conf" ] || return 0
    [ "$(sed -n 's/^enabled=//p' "$_zsce_conf" 2>/dev/null | head -n1 | tr -d ' \r')" != 0 ]
}

ziyu_slot_coverage_expand() {
    _zsce_mod=$(_ziyu_slot_module "${1:-}")
    _zsce_stage_fonts="${2:-}"
    _zsce_payload_fonts="${3:-}"
    ziyu_slot_coverage_enabled "$_zsce_mod" || return 0
    [ -d "$_zsce_stage_fonts" ] || return 0
    [ -d "$_zsce_payload_fonts" ] || return 0
    _zsce_added=0
    # 诊断用：config/tools/slot-coverage-limit.conf 的 limit=N 只扩展前 N 个槽位，
    # 用于二分定位哪个槽位会导致开机异常。
    _zsce_limit=''
    _zsce_limit_conf="$_zsce_mod/config/tools/slot-coverage-limit.conf"
    [ -s "$_zsce_limit_conf" ] && _zsce_limit=$(sed -n 's/^limit=//p' "$_zsce_limit_conf" 2>/dev/null | head -n1 | tr -d ' \r')
    case "$_zsce_limit" in ''|*[!0-9]*) _zsce_limit='' ;; esac
    _zsce_log="$_zsce_mod/logs/slot-coverage.log"
    _zsce_plan=$(ziyu_slot_coverage_plan "$_zsce_mod" "$_zsce_payload_fonts" 2>/dev/null) || return 0
    [ -n "$_zsce_plan" ] || return 0
    while IFS='|' read -r _zsce_src _zsce_slot; do
        [ -n "$_zsce_src" ] && [ -n "$_zsce_slot" ] || continue
        [ -f "$_zsce_src" ] || continue
        case "$_zsce_slot" in *.ttc|*.TTC) continue ;; esac
        case "$(printf '%s' "$_zsce_slot" | tr 'A-Z' 'a-z')" in *emoji*|*color*|*clock*) continue ;; esac
        [ -e "$_zsce_stage_fonts/$_zsce_slot" ] && continue
        ln -f "$_zsce_src" "$_zsce_stage_fonts/$_zsce_slot" 2>/dev/null || \
            cp -f "$_zsce_src" "$_zsce_stage_fonts/$_zsce_slot" 2>/dev/null || continue
        _zsce_added=$((_zsce_added + 1))
        printf '[SLOT-COVERAGE] add=%s\n' "$_zsce_slot" >> "$_zsce_log" 2>/dev/null || true
        [ -n "$_zsce_limit" ] && [ "$_zsce_added" -ge "$_zsce_limit" ] && break
    done <<EOF
$_zsce_plan
EOF
    if [ "$_zsce_added" -gt 0 ]; then
        _zsce_total=0; _zsce_cjk=0; _zsce_digit=0; _zsce_latin=0
        while IFS='|' read -r _zsce_src2 _zsce_slot2; do
            [ -n "$_zsce_slot2" ] || continue
            _zsce_total=$((_zsce_total + 1))
            case "$(ziyu_slot_classify "$_zsce_slot2")" in
                cjk) _zsce_cjk=$((_zsce_cjk + 1)) ;;
                digit) _zsce_digit=$((_zsce_digit + 1)) ;;
                *) _zsce_latin=$((_zsce_latin + 1)) ;;
            esac
        done <<EOF
$_zsce_plan
EOF
        _zsce_boot=''
        IFS= read -r _zsce_boot < /proc/sys/kernel/random/boot_id 2>/dev/null || _zsce_boot=''
        mkdir -p "$_zsce_mod/config" "$_zsce_mod/logs" 2>/dev/null
        {
            printf 'schema=ziyu-slot-coverage-v1\n'
            printf 'rom=%s\n' "$(ziyu_slot_rom "$_zsce_mod")"
            printf 'total=%s\ncjk=%s\ndigit=%s\nlatin=%s\n' "$_zsce_total" "$_zsce_cjk" "$_zsce_digit" "$_zsce_latin"
            printf 'boot=%s\n' "$_zsce_boot"
            printf 'font=%s\n' "$(head -n1 "$_zsce_mod/config/active_font.conf" 2>/dev/null | tr -d '\r\n')"
        } > "$_zsce_mod/config/slot-coverage.conf" 2>/dev/null || true
        printf '[SLOT-COVERAGE] rom=%s expanded=%s total=%s cjk=%s digit=%s latin=%s\n' \
            "$(ziyu_slot_rom "$_zsce_mod")" "$_zsce_added" "$_zsce_total" "$_zsce_cjk" "$_zsce_digit" "$_zsce_latin" \
            >> "$_zsce_mod/logs/slot-coverage.log" 2>/dev/null
    fi
    return 0
}
