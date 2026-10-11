#!/system/bin/sh
# Standalone, read-only Android font topology and failure report.
# Run from a root shell when possible: su -c sh /path/to/font_partition_compat_report.sh
# The report is written beside this script. This script never invokes the App.
set +e

case "$0" in
    */*) _fpc_self_dir=${0%/*} ;;
    *) _fpc_self_dir=. ;;
esac
_fpc_script_dir=$(CDPATH= cd -- "$_fpc_self_dir" 2>/dev/null && pwd)
[ -n "$_fpc_script_dir" ] || { echo '无法确定脚本目录' >&2; exit 1; }

_fpc_find_module() {
    _fpc_candidate=${MODDIR:-${MODULE_DIR:-}}
    if [ -n "$_fpc_candidate" ] && [ -f "$_fpc_candidate/module.prop" ]; then
        printf '%s\n' "$_fpc_candidate"
        return
    fi
    _fpc_candidate=$(CDPATH= cd -- "$_fpc_script_dir/.." 2>/dev/null && pwd)
    if [ -n "$_fpc_candidate" ] && [ -f "$_fpc_candidate/module.prop" ]; then
        printf '%s\n' "$_fpc_candidate"
        return
    fi
    for _fpc_root in /data/adb/modules /data/adb/modules_update; do
        for _fpc_candidate in "$_fpc_root"/*; do
            [ -f "$_fpc_candidate/module.prop" ] || continue
            _fpc_id=$(sed -n 's/^id=//p' "$_fpc_candidate/module.prop" 2>/dev/null | head -n1)
            case "$_fpc_id" in LuoShu|Ziyu) printf '%s\n' "$_fpc_candidate"; return ;; esac
        done
    done
}

_fpc_moddir=$(_fpc_find_module)
_fpc_config="$_fpc_moddir/config/device_font_partitions.conf"
_fpc_stamp=$(date '+%Y%m%d-%H%M%S' 2>/dev/null)
[ -n "$_fpc_stamp" ] || _fpc_stamp=unknown
_fpc_report="$_fpc_script_dir/font-partition-compat-$_fpc_stamp.log"
[ ! -e "$_fpc_report" ] || _fpc_report="$_fpc_script_dir/font-partition-compat-$_fpc_stamp-$$.log"
_fpc_work="$_fpc_script_dir/.font-partition-compat-$$"
(umask 077 && mkdir "$_fpc_work") 2>/dev/null || { echo '脚本同目录不可写，无法创建临时文件。' >&2; exit 1; }
_fpc_rows="$_fpc_work/partitions"
_fpc_xmls="$_fpc_work/xmls"
_fpc_routes="$_fpc_work/routes"
_fpc_entries="$_fpc_work/entries"
_fpc_report_tmp="$_fpc_work/report"
: > "$_fpc_rows"; : > "$_fpc_xmls"; : > "$_fpc_routes"; : > "$_fpc_entries"
_fpc_cleanup() {
    rm -f "$_fpc_rows" "$_fpc_xmls" "$_fpc_routes" "$_fpc_entries" "$_fpc_report_tmp" 2>/dev/null
    rmdir "$_fpc_work" 2>/dev/null
}
trap '_fpc_cleanup' 0 HUP INT TERM

_fpc_uid=$(id -u 2>/dev/null)
_fpc_brand=$(getprop ro.product.brand 2>/dev/null)
_fpc_manufacturer=$(getprop ro.product.manufacturer 2>/dev/null)
_fpc_model=$(getprop ro.product.model 2>/dev/null)
_fpc_device=$(getprop ro.product.device 2>/dev/null)
_fpc_android=$(getprop ro.build.version.release 2>/dev/null)
_fpc_sdk=$(getprop ro.build.version.sdk 2>/dev/null)
_fpc_rom=$(getprop ro.build.display.id 2>/dev/null)
_fpc_label="$_fpc_brand $_fpc_model"
[ -n "$(printf '%s' "$_fpc_label" | tr -d ' ')" ] || _fpc_label=${_fpc_manufacturer:-未知设备}
if [ "$_fpc_uid" = 0 ]; then
    printf '检测到 Root 权限；设备：%s，正在细查字体分区、XML 路由和失败原因...\n' "$_fpc_label"
else
    printf '当前 UID=%s（非 Root）；正在扫描可访问内容，受限目录会标记为缺失。\n' "${_fpc_uid:-未知}"
fi

_fpc_add_partition() {
    _fpc_name=$1; _fpc_origin=$2
    case "$_fpc_name" in
        ''|[0-9]*|_*|*[!A-Za-z0-9_]*) return ;;
        acct|apex|cache|config|data|data_mirror|debug_ramdisk|dev|linkerconfig|metadata|mnt|proc|sdcard|storage|sys|tmp|vendor_dlkm|odm_dlkm|system_dlkm) return ;;
    esac
    awk -F'|' -v n="$_fpc_name" '$1==n{f=1} END{exit !f}' "$_fpc_rows" && return
    printf '%s|%s\n' "$_fpc_name" "$_fpc_origin" >> "$_fpc_rows"
}
_fpc_xml_candidate() {
    [ -f "$1" ] || return 1
    case "${1##*/}" in *font*.xml|*Font*.xml|*FONT*.xml) return 0 ;; esac
    return 1
}
_fpc_is_font() {
    case "$(printf '%s' "${1##*.}" | tr '[:upper:]' '[:lower:]')" in ttf|otf|ttc|otc) return 0 ;; esac
    return 1
}
_fpc_state() {
    if [ -f "$1" ] && [ -r "$1" ]; then printf readable
    elif [ -e "$1" ] || [ -L "$1" ]; then printf present-unreadable
    else printf missing
    fi
}
_fpc_bytes() { [ -f "$1" ] && wc -c < "$1" 2>/dev/null | tr -d '[:space:]'; }
_fpc_count_fonts() {
    _n=0
    for _f in "$1"/*; do [ -f "$_f" ] && _fpc_is_font "$_f" && _n=$((_n+1)); done
    printf '%s' "$_n"
}
_fpc_mount_for() {
    awk -v target="$2" '
      { m=$5; if (m=="/" || target==m || index(target,m "/")==1) if (length(m)>best) {best=length(m); row=$0} }
      END {if(row!="") print row; else print "未识别到覆盖该路径的挂载点"}
    ' "$1" 2>/dev/null
}
_fpc_mount_evidence() {
    _path=$1
    for _view in self 1; do
        if [ "$_view" = self ]; then _mi=/proc/self/mountinfo; _label='当前脚本进程'
        else _mi=/proc/1/mountinfo; _label='PID 1 系统进程'
        fi
        if [ -r "$_mi" ]; then _row=$(_fpc_mount_for "$_mi" "$_path"); else _row='mountinfo 不可读'; fi
        printf '  %s 挂载：%s\n' "$_label" "$_row"
    done
    printf '  PID 1 根目录视图：%s（%s）\n' "/proc/1/root$_path" "$(_fpc_state "/proc/1/root$_path")"
}

# Keep common partition names, module-discovered names, and OEM top-level roots.
for _part in system system_ext product vendor odm oem my_product my_engineering my_company my_preload my_region my_stock oplus_product oplus_engineering oplus_version oplus_region mi_ext cust hw_product; do
    _fpc_add_partition "$_part" 'Android 常见逻辑分区'
done
if [ -r "$_fpc_config" ]; then
    while IFS= read -r _part || [ -n "$_part" ]; do
        _part=$(printf '%s' "$_part" | tr -d '\r')
        case "$_part" in ''|\#*) continue ;; esac
        _fpc_add_partition "$_part" '模块设备分区配置'
    done < "$_fpc_config"
fi
for _root in /*; do
    [ -d "$_root" ] || continue
    _part=${_root#/}
    case "$_part" in
        ''|[0-9]*|_*|*[!A-Za-z0-9_]*|acct|apex|cache|config|data|data_mirror|debug_ramdisk|dev|linkerconfig|metadata|mnt|proc|sdcard|storage|sys|tmp|vendor_dlkm|odm_dlkm|system_dlkm) continue ;;
    esac
    [ -d "$_root/fonts" ] && { _fpc_add_partition "$_part" '根目录 fonts/'; continue; }
    for _xml in "$_root"/etc/*font*.xml "$_root"/etc/*Font*.xml "$_root"/etc/*FONT*.xml; do
        if _fpc_xml_candidate "$_xml"; then _fpc_add_partition "$_part" '根目录 etc 字体 XML'; break; fi
    done
done

# Parse <font weight="400" style="normal">name.ttf</font>, including multiline entries.
_fpc_parse_xml() {
    awk '
      function trim(s){sub(/^[ \t]+/,"",s);sub(/[ \t]+$/,"",s);return s}
      function attr(s,k,l,p,r){l=tolower(s);p=index(l,k "=\"");if(!p)return "";r=substr(s,p+length(k)+2);sub(/\".*/,"",r);return r}
      {
        l=tolower($0)
        if(pending==""){if(match(l,/<font([ \t>])/)){pending=$0;start=NR}}
        else pending=pending " " $0
        if(pending!=""){
          l=tolower(pending);op=index(l,"<font");gt=index(substr(l,op),">")
          if(op&&gt){rest=substr(l,op+gt);cl=index(rest,"</font")
            if(cl){
              tag=substr(pending,op,gt);ref=trim(substr(pending,op+gt,cl-1));sub(/[ \t].*/,"",ref)
              if(ref~/\.(ttf|otf|ttc|otc)$/) print ref "|" attr(tag,"weight") "|" attr(tag,"style") "|" start
              pending=""
            }
          }
          if(length(pending)>4096)pending=""
        }
      }
    ' "$1" 2>/dev/null
}

printf '%s\n' '正在读取系统字体 XML...'
_fpc_xml_limit=256
_fpc_xml_total=0
while IFS='|' read -r _part _origin; do
    _etc="/$_part/etc"
    [ -d "$_etc" ] || continue
    for _xml in "$_etc"/*font*.xml "$_etc"/*Font*.xml "$_etc"/*FONT*.xml; do
        _fpc_xml_candidate "$_xml" || continue
        grep -Fqx "$_part|$_xml" "$_fpc_xmls" 2>/dev/null && continue
        _fpc_xml_total=$((_fpc_xml_total+1))
        [ "$_fpc_xml_total" -le "$_fpc_xml_limit" ] || break
        printf '%s|%s\n' "$_part" "$_xml" >> "$_fpc_xmls"
        _fpc_parse_xml "$_xml" > "$_fpc_entries"
        while IFS='|' read -r _ref _weight _style _line || [ -n "$_ref" ]; do
            [ -n "$_ref" ] || continue
            _kind=relative; _resolved=
            case "$_ref" in
              /*)
                _kind=absolute; _resolved=$_ref
                _target=${_ref#/}; _target=${_target%%/*}
                _fpc_add_partition "$_target" 'XML 绝对字体路径'
                ;;
              *)
                for _try in "/$_part/fonts/$_ref" "/$_part/etc/fonts/$_ref" "/$_part/$_ref"; do
                    if [ -f "$_try" ]; then _resolved=$_try; break; fi
                done
                # OEM XML in system_ext/etc commonly references shared fonts.
                # Match the inventory scanner's cross-partition candidate search
                # instead of reporting every relative reference as missing.
                if [ -z "$_resolved" ]; then
                    for _font_part in system system_ext product vendor odm oem my_product my_engineering my_company my_preload my_region my_stock oplus_product oplus_engineering oplus_version oplus_region mi_ext cust hw_product; do
                        _try="/$_font_part/fonts/$_ref"
                        if [ -f "$_try" ]; then _resolved=$_try; break; fi
                    done
                fi
                ;;
            esac
            _self_state=missing; _pid_state=missing; _size=
            if [ -n "$_resolved" ]; then
                _self_state=$(_fpc_state "$_resolved")
                _pid_state=$(_fpc_state "/proc/1/root$_resolved")
                _size=$(_fpc_bytes "$_resolved")
            fi
            printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n' \
              "$_part" "$_kind" "$_ref" "${_weight:-未声明}" "${_style:-未声明}" \
              "$_xml" "$_line" "$_self_state" "$_pid_state" "${_resolved:-未解析}" "${_size:-未知}" >> "$_fpc_routes"
        done < "$_fpc_entries"
    done
    [ "$_fpc_xml_total" -le "$_fpc_xml_limit" ] || break
done < "$_fpc_rows"
_fpc_xml_scanned=$((_fpc_xml_total < _fpc_xml_limit ? _fpc_xml_total : _fpc_xml_limit))
_fpc_routes_count=$(wc -l < "$_fpc_routes" | tr -d '[:space:]')
printf '扫描完成：字体 XML %s 个，提取具体字体路由 %s 条。正在导出日志...\n' "$_fpc_xml_scanned" "${_fpc_routes_count:-0}"

{
    printf '%s\n' '字域字体分区与失败路由兼容性诊断' '========================================================================'
    printf '生成时间：%s\n' "$(date '+%Y-%m-%d %H:%M:%S %z' 2>/dev/null || date 2>/dev/null || echo unknown)"
    printf '设备品牌：%s\n设备制造商：%s\n设备型号：%s\n产品代号：%s\n' "${_fpc_brand:-未读取到}" "${_fpc_manufacturer:-未读取到}" "${_fpc_model:-未读取到}" "${_fpc_device:-未读取到}"
    printf 'Android：%s / SDK %s\nROM：%s\n脚本 UID：%s\n' "${_fpc_android:-未读取到}" "${_fpc_sdk:-未读取到}" "${_fpc_rom:-未读取到}" "${_fpc_uid:-未知}"
    if [ -n "$_fpc_moddir" ]; then
        printf '模块目录：%s\n模块版本：%s (%s)\n' "$_fpc_moddir" "$(sed -n 's/^version=//p' "$_fpc_moddir/module.prop" 2>/dev/null | head -n1)" "$(sed -n 's/^versionCode=//p' "$_fpc_moddir/module.prop" 2>/dev/null | head -n1)"
    else
        printf '%s\n' '模块目录：未找到；模块错误与配置章节会跳过。'
    fi
    printf '已扫描 XML：%s / 上限 %s；已解析路由：%s 条\n' "$_fpc_xml_scanned" "$_fpc_xml_limit" "${_fpc_routes_count:-0}"
    [ "$_fpc_xml_total" -le "$_fpc_xml_limit" ] || printf '%s\n' '提示：XML 数量达到扫描上限，后续 XML 未读取。'
    printf '%s\n' '只读说明：不启动 App、不挂载/卸载、不改写模块配置；报告写在本脚本同目录。'
    printf '%s\n\n' 'PID 1 视图是 Android 系统进程的路径证据；Root Shell 自己可见不等于系统已经加载。'
    printf '%s\n\n' '相对字体先查询 XML 所在分区，再查询共享字体分区；解析路径为可读候选，不能单独证明 OEM 实际加载顺序。'

    printf '%s\n' '一、字体分区候选与挂载证据' '------------------------------------------------------------------------'
    _evidence=0
    while IFS='|' read -r _part _origin; do
        _dir="/$_part/fonts"
        _count=$(_fpc_count_fonts "$_dir")
        _xml_count=$(awk -F'|' -v p="$_part" '$1==p{n++}END{print n+0}' "$_fpc_xmls")
        _abs=$(awk -F'|' -v p="$_part" '$1==p&&$2=="absolute"{n++}END{print n+0}' "$_fpc_routes")
        _abs_pid=$(awk -F'|' -v p="$_part" '$1==p&&$2=="absolute"&&$9=="readable"{n++}END{print n+0}' "$_fpc_routes")
        _rel=$(awk -F'|' -v p="$_part" '$1==p&&$2=="relative"{n++}END{print n+0}' "$_fpc_routes")
        if [ -d "$_dir" ] || [ "$_xml_count" -gt 0 ] || [ "$_abs" -gt 0 ]; then
            _evidence=$((_evidence+1))
            if [ "$_abs_pid" -gt 0 ]; then _assessment='强候选：PID 1 可读到 XML 绝对路由目标'
            elif [ "$_abs" -gt 0 ]; then _assessment='待核实：XML 有绝对路由，PID 1 未确认可读'
            elif [ "$_rel" -gt 0 ] && [ "$_count" -gt 0 ]; then _assessment='候选：XML 使用相对路由且字体目录有文件'
            elif [ "$_count" -gt 0 ]; then _assessment='待核实：有字体文件，未找到 XML 明确路由'
            else _assessment='证据不足：发现 XML/字体目录，未解析出可用目标'; fi
            printf '[%s] %s（来源：%s）\n' "$_part" "$_assessment" "$_origin"
            printf '  %s：目录=%s 可读=%s 直接字体文件=%s 个；XML=%s 个；绝对路由=%s（PID1可读=%s）；相对路由=%s\n' \
              "$_dir" "$([ -d "$_dir" ] && printf 存在 || printf 不存在)" "$([ -r "$_dir" ] && printf 是 || printf 否)" "$_count" "$_xml_count" "$_abs" "$_abs_pid" "$_rel"
            _fpc_mount_evidence "$_dir"
        fi
    done < "$_fpc_rows"
    printf '有证据候选分区：%s 个。\n\n' "$_evidence"

    printf '%s\n' '二、逐项 XML 路由（文件名、字重、样式、可见性）' '------------------------------------------------------------------------'
    if [ ! -s "$_fpc_routes" ]; then
        printf '%s\n' '没有解析到 <font> 文件项；可能是 XML 格式特殊或不可读，请看后面的 XML 原文摘录。'
    else
        while IFS='|' read -r _part _kind _ref _weight _style _xml _line _self _pid _resolved _size; do
            printf '[%s] %s:%s  weight=%s style=%s\n' "$_kind" "$_xml" "$_line" "$_weight" "$_style"
            printf '  字体项=%s；解析路径=%s；执行进程=%s；PID1=%s；大小=%s 字节\n' "$_ref" "$_resolved" "$_self" "$_pid" "$_size"
        done < "$_fpc_routes"
    fi

    printf '\n%s\n' '三、XML 关键原文（family/font/weight/style/axis/字体文件名）' '------------------------------------------------------------------------'
    while IFS='|' read -r _part _xml; do
        printf 'XML：%s\n' "$_xml"
        grep -Ein '(<family|</family|<font|</font|weight=|style=|<axis|</axis|<alias|name=|lang=|variant=|fallbackfor|\.ttf|\.otf|\.ttc|\.otc)' "$_xml" 2>/dev/null | head -n 80 | cut -c 1-600
    done < "$_fpc_xmls"
    [ -s "$_fpc_xmls" ] || printf '%s\n' '没有可读取字体 XML。'

    printf '\n%s\n' '四、分区 fonts/ 直接字体文件清单（最多 800 项）' '------------------------------------------------------------------------'
    _seen=0; _truncated=0
    while IFS='|' read -r _part _origin; do
        _dir="/$_part/fonts"; [ -d "$_dir" ] || continue
        for _file in "$_dir"/*; do
            _fpc_is_font "$_file" || continue
            _seen=$((_seen+1))
            if [ "$_seen" -gt 800 ]; then _truncated=1; break; fi
            _bytes=$(_fpc_bytes "$_file"); _mode=$(ls -ld "$_file" 2>/dev/null | awk '{print $1}'); _link=$(readlink "$_file" 2>/dev/null)
            printf '%s | %s | %s bytes | mode=%s | %s' "$_file" "$(_fpc_state "$_file")" "${_bytes:-未知}" "${_mode:-未知}" "$_origin"
            [ -n "$_link" ] && printf ' | symlink -> %s' "$_link"
            printf '\n'
        done
        [ "$_truncated" = 1 ] && break
    done < "$_fpc_rows"
    [ "$_seen" -gt 0 ] || printf '%s\n' '未枚举到字体文件。'
    [ "$_truncated" = 0 ] || printf '%s\n' '达到 800 项上限，后续未列出。'

    printf '\n%s\n' '五、已安装模块的挂载和字体验证配置' '------------------------------------------------------------------------'
    if [ -z "$_fpc_moddir" ]; then
        printf '%s\n' '没有找到模块目录。可使用 MODDIR=/data/adb/modules/<目录> 指定，建议以 Root 执行。'
    else
        for _name in \
          config/mount-backend.conf config/mount-backend-preference.conf \
          config/device-font-load-verification.conf config/device-font-load-route-verification.json \
          config/self-mount.conf config/active_font.conf config/device-font-engine.conf \
          config/font-payload-manifest.conf config/font-target-coverage.conf \
          config/universal-font-cutover.conf config/universal-font-runtime-verification.conf \
          config/universal-font-rollback.conf; do
            _file="$_fpc_moddir/$_name"; [ -s "$_file" ] || continue
            printf '\n[%s]\n' "$_name"
            case "$_name" in
              config/font-payload-manifest.conf) head -n 120 "$_file" 2>/dev/null ;;
              *.json) grep -Eio '.{0,80}(blocked|status|state|reason|failure|error|target|logicalPath|artifactId|verification).{0,180}' "$_file" 2>/dev/null | head -n 120 | cut -c 1-500 ;;
              *) head -n 100 "$_file" 2>/dev/null ;;
            esac
        done

        printf '\n%s\n' '模块日志中的失败、blocked artifact、fallback、挂载和验证目标摘录（每份最多 120 行）'
        _logs=0
        for _name in logs/fontswitch.log logs/device-font-load-verify.log logs/mount-backend.log \
          logs/font-route-verify.log logs/mount.log logs/self-mount.log logs/mount-diagnostics.log logs/soft-reboot.log logs/universal-runtime.log logs/service.log; do
            _file="$_fpc_moddir/$_name"; [ -s "$_file" ] || continue
            printf '\n[%s]\n' "$_name"
            if [ "$_name" = logs/mount-diagnostics.log ]; then
                tail -n 600 "$_file" 2>&1
                _logs=$((_logs+1))
                continue
            fi
            grep -Ei 'blocked|artifact|prepare failed|cutover|fallback|universal|font_route|route|partition|target|logicalPath|pid.?1|verify|verif|fail|error|denied|mount|payload' "$_file" 2>/dev/null | tail -n 120 | cut -c 1-700
            _logs=$((_logs+1))
        done
        [ "$_logs" -gt 0 ] || printf '%s\n' '没有找到可读的相关模块日志。'

        printf '\n%s\n' 'Universal 编译 artifact manifest 中的阻止/拒绝原因'
        _json_count=0
        for _file in "$_fpc_moddir/.luoshu-payload/.luoshu-runtime/deployment/artifact-manifest.json" \
          "$_fpc_moddir"/config/universal-font-artifact-manifests/*.json \
          "$_fpc_moddir"/cache/universal-font-artifacts/*/manifest.json; do
            [ -s "$_file" ] || continue
            _json_count=$((_json_count+1)); [ "$_json_count" -le 12 ] || { printf '%s\n' 'JSON 达到 12 个上限。'; break; }
            printf '\n[%s]\n' "${_file#$_fpc_moddir/}"
            grep -Eio '.{0,120}(blocked|status|state|reason|failure|error|rejected|unsafe|artifactId|target|logicalPath|sourcePath|outputPath).{0,220}' "$_file" 2>/dev/null | head -n 100 | cut -c 1-600
        done
        [ "$_json_count" -gt 0 ] || printf '%s\n' '没有发现保留的 Universal artifact JSON 清单。'
    fi

    printf '\n%s\n' '六、PID 1 与脚本进程字体/分区挂载项' '------------------------------------------------------------------------'
    for _mi in /proc/self/mountinfo /proc/1/mountinfo; do
        printf '[%s]\n' "$_mi"
        if [ -r "$_mi" ]; then
            grep -E '(^|/)(system|system_ext|product|vendor|odm|oem|oplus_[^ /]+|mi_ext|cust|hw_product)(/| )|/fonts?(/|$)|font_fallback\.xml|fonts\.xml' "$_mi" 2>/dev/null | head -n 160
            [ -n "$(grep -E '(^|/)(system|system_ext|product|vendor|odm|oem|oplus_[^ /]+|mi_ext|cust|hw_product)(/| )|/fonts?(/|$)|font_fallback\.xml|fonts\.xml' "$_mi" 2>/dev/null | head -n1)" ] || printf '%s\n' '没有匹配的字体/分区挂载项。'
        else printf '%s\n' '不可读（可能是权限或系统限制）。'; fi
    done

    printf '\n%s\n' 'OverlayFS 能力与相关内核日志（只读采集，不尝试挂载）'
    uname -a 2>&1
    id 2>&1
    getenforce 2>&1
    for _ns in /proc/self/ns/mnt /proc/1/ns/mnt; do
        printf '%s: ' "$_ns"; readlink "$_ns" 2>&1
    done
    cat /proc/filesystems 2>&1
    _params=0
    for _param in /sys/module/overlay/parameters/*; do
        [ -f "$_param" ] || continue
        _params=1
        printf '%s=' "${_param##*/}"; cat "$_param" 2>&1
    done
    [ "$_params" = 1 ] || printf '%s\n' 'OverlayFS 参数目录不存在或不可读。'
    dmesg > "$_fpc_report_tmp.kernel" 2>&1
    _kernel_rc=$?
    printf 'dmesg rc=%s；以下是历史快照，不能单独证明最近一次失败原因。\n' "$_kernel_rc"
    if [ "$_kernel_rc" -eq 0 ]; then
        grep -Ei 'overlay|avc:.*denied|kernelsu|sukisu|nomount' "$_fpc_report_tmp.kernel" | tail -n 120
    else
        head -n 8 "$_fpc_report_tmp.kernel"
    fi
    rm -f "$_fpc_report_tmp.kernel" 2>/dev/null

    printf '\n%s\n' '七、适配判断提示' '------------------------------------------------------------------------'
    printf '%s\n' '1. 优先以 PID 1 可读的具体字体文件和 XML 路由为准；字体目录存在本身不能证明它是 UI 字体槽位。'
    printf '%s\n' '2. 对照 XML 的 weight/style、文件名、真实分区和最近挂载项；相对路径解析是候选，需结合 OEM 规则复核。'
    printf '%s\n' '3. 若 Universal 拒绝部署，检查 CUTOVER prepare failed 行、artifact manifest 的 status/reason/target 和 PID 1 路由验证目标。'
    printf '%s\n' '4. Root Shell 可读但 PID 1 不可读，表示两者的路径/挂载视图不同；不能据此宣称 Android 已加载该字体。'
    printf '%s\n' '5. 报告是只读现场证据，不会替设备改挂载实现或执行挂载/卸载；请把该日志发给开发者做机型适配。'
    printf '%s\n' '6. 非 Root 运行可能缺少 /data/adb 与受限分区信息，请注明运行权限。'
} > "$_fpc_report_tmp" 2>&1

if [ ! -s "$_fpc_report_tmp" ] || ! mv "$_fpc_report_tmp" "$_fpc_report" 2>/dev/null; then
    echo '诊断报告写入失败，请确认脚本目录可写。' >&2
    exit 1
fi
chmod 0644 "$_fpc_report" 2>/dev/null
printf '诊断完成，日志已生成：%s\n' "$_fpc_report"
