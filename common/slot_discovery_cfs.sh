#!/system/bin/sh
# 槽位探测（全覆盖口径）—— 移植自 custom-font-switcher 的 slots.sh 探测核心。
#
# 来源：custom-font-switcher v1.5.3（作者 暗影双生，github.com/ayss2188/custom-font-switcher）
# 仅对齐其「四路来源合并 + 角色分类 + 保护名单」的探测思路；本文件为字域独立实现，
# 脚本代码重写，未复制对方脚本。详见 THIRD_PARTY_NOTICES.md。
#
# 作用：纯 shell 枚举本机字体槽位，覆盖更全（比 device_slot_discovery.py 多一路
#   「目录扫描」，能发现 XML 与机型清单都没覆盖到的各语种 Noto / 装饰字体）。
#   输出与 device_slot_discovery.py 相同格式的 config/device-slots.list 缓存，
#   下游 slot_coverage.sh 无需改动即可消费。
#
# 分类口径（与 custom-font-switcher 的角色对齐，映射到字域四类）：
#   cjk    中/日/韩汉字系（Hans/Hant/CJK/思源/鸿蒙/兰亭…）
#   digit  时钟/数字（Clock/Digit/DIN/OSans-Solid…）
#   latin  拉丁主力与装饰（Roboto/OPlus/SysFont/圆体/手写…）
#   lang   其它文字（阿拉伯/希伯来/泰/天城/缅/藏/日韩假名…）
#   skip   永不替换（protect.list、粗细调节族、emoji/符号/兜底/彩色）
# 相比原 Python 版：无需捆绑 Python 运行时；覆盖更全并带角色保护。
set +e

# ---------------------------------------------------------------------------
# 基础工具（/system/bin/sh 兼容，不用 bash 扩展）
# ---------------------------------------------------------------------------
_zcsf_lc() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

_zcsf_trim() {
    _zcsf_t="$1"
    case "$_zcsf_t" in
        ' '*) _zcsf_t=${_zcsf_t# };;
    esac
    case "$_zcsf_t" in
        *' ') _zcsf_t=${_zcsf_t% };;
    esac
    _zcsf_t=$(printf '%s' "$_zcsf_t" | tr -d '\r')
    printf '%s' "$_zcsf_t"
}

_zcsf_isnum() { case "${1:-}" in ''|*[!0-9]*) return 1 ;; esac; return 0; }

# ---------------------------------------------------------------------------
# 目标 ROM：优先配置，其次系统属性，最后回退 coloros（对齐 slot_coverage.sh 口径）
# ---------------------------------------------------------------------------
_zcsf_rom() {
    _zcr_mod="$1"
    _zcr_rom=''
    if [ -f "$_zcr_mod/config/slot_coverage_rom.conf" ]; then
        _zcr_rom=$(sed -n 's/^rom=//p' "$_zcr_mod/config/slot_coverage_rom.conf" 2>/dev/null | head -n1 | tr -d ' \r')
    fi
    if [ -z "$_zcr_rom" ]; then
        case "$(getprop ro.build.version.oplusrom 2>/dev/null)$(getprop ro.build.version.opporom 2>/dev/null)" in
            '') ;;
            *) _zcr_rom=coloros ;;
        esac
    fi
    if [ -z "$_zcr_rom" ]; then
        case "$(getprop ro.miui.ui.version.name 2>/dev/null)" in
            '') ;;
            *) _zcr_rom=hyperos ;;
        esac
    fi
    if [ -z "$_zcr_rom" ]; then
        case "$(getprop ro.vivo.os.version 2>/dev/null)$(getprop ro.vivo.os.build.display.id 2>/dev/null)" in
            '') ;;
            *) _zcr_rom=originos ;;
        esac
    fi
    if [ -z "$_zcr_rom" ]; then
        case "$(getprop ro.build.display.id 2>/dev/null)" in
            *Flyme*|*flyme*) _zcr_rom=flyme ;;
        esac
    fi
    [ -n "$_zcr_rom" ] || _zcr_rom=coloros
    printf '%s' "$_zcr_rom"
}

# ---------------------------------------------------------------------------
# 真实存在的字体目录（对齐 slots.sh 的 slot_dirs）
# ---------------------------------------------------------------------------
_zcsf_font_dirs() {
    {
        for _zcd_d in /system/fonts /system_ext/fonts /product/fonts /vendor/fonts /odm/fonts /oem/fonts \
            /my_product/fonts /my_region/fonts /my_company/fonts /my_carrier/fonts /my_stock/fonts \
            /my_heytap/fonts /my_manifest/fonts /my_bigball/fonts /mi_ext/fonts /hw_product/fonts \
            /prism/fonts /optics/fonts /product/vivo/fonts /system_ext/vivo/fonts /vendor/vivo/fonts \
            /oplus_product/fonts /oplus_engineering/fonts /oplus_version/fonts /oplus_region/fonts; do
            [ -d "$_zcd_d" ] && echo "$_zcd_d"
        done
        # 未知厂商分区自动发现：/xxx/fonts 与 /xxx/yyy/fonts
        for _zcd_g in /*/fonts /*/*/fonts; do
            [ -d "$_zcd_g" ] || continue
            _zcd_p=${_zcd_g%/fonts}
            [ -L "$_zcd_p" ] && continue
            case "$_zcd_g" in
                /data/*|/sdcard/*|/storage/*|/mnt/*|/proc/*|/sys/*|/dev/*|/apex/*|/linkerconfig/*|/config/*|/metadata/*|/cache/*|/acct/*) continue ;;
            esac
            echo "$_zcd_g"
        done
    } | sort -u
}

# ---------------------------------------------------------------------------
# 字体配置 XML 文件列表（对齐 slots.sh 的 slot_xml_files）
# ---------------------------------------------------------------------------
_zcsf_xml_files() {
    {
        for _zcx_x in /system/etc/*font*.xml /system_ext/etc/*font*.xml /product/etc/*font*.xml \
            /vendor/etc/*font*.xml /odm/etc/*font*.xml /*/etc/*font*.xml /*/etc/flyme_*.xml; do
            [ -f "$_zcx_x" ] && echo "$_zcx_x"
        done
    } | sort -u
}

# ---------------------------------------------------------------------------
# 从 XML 提取 <family> 里引用的字体文件名（对齐 slot_scan_names 的 awk 快速通道）
# 输出：一行一个文件名（含路径的只留 basename）
# ---------------------------------------------------------------------------
_zcsf_xml_names() {
    _zcsf_awk='
{
  buf = buf $0 "\n"
}
END {
  n = split(buf, parts, "<family")
  for (i = 2; i <= n; i++) {
    chunk = parts[i]
    gt = index(chunk, ">")
    if (gt <= 0) continue
    hdr = substr(chunk, 1, gt - 1)
    body = substr(chunk, gt + 1)
    name = ""
    if (match(hdr, /name="[^"]*"/)) name = substr(hdr, RSTART + 6, RLENGTH - 7)
    lang = ""
    if (match(hdr, /lang="[^"]*"/)) lang = substr(hdr, RSTART + 6, RLENGTH - 7)
    # 族名过滤（对齐 cfs 的 slot_scan_names / _xml_family_ok）
    low = tolower(name)
    if (low ~ /emoji|symbol|mono|cursive|casual|math|music|clock/) continue
    if (low ~ /serif/ && low !~ /sans-serif|sansserif/) continue
    # 语言标签（对齐 cfs 的 _xml_family_ok）：只认 zh* / und-latn* / en / en-* / 空；
    #   ja、ko、und-Ahom、und-Arab、und-Beng 等一律不碰（这些走目录扫描的角色分类）
    ll = tolower(lang)
    if (ll != "" && ll !~ /zh/ && ll !~ /und-latn/ && ll != "en" && ll !~ /^en-/) continue
    b = body
    while (match(b, /[A-Za-z0-9_.-]+[.](ttf|otf|ttc|TTF|OTF|TTC)/)) {
      fn = substr(b, RSTART, RLENGTH)
      print fn
      b = substr(b, RSTART + RLENGTH)
    }
  }
}
'
    for _zcx_x in $(_zcsf_xml_files); do
        [ -f "$_zcx_x" ] || continue
        awk "$_zcsf_awk" "$_zcx_x" 2>/dev/null
    done | sort -u
}

# ---------------------------------------------------------------------------
# 机型清单（slot_targets/<rom>.list）里的字体文件名
# ---------------------------------------------------------------------------
_zcsf_targets_names() {
    _zct_mod="$1"
    _zct_rom="$2"
    for _zct_list in "$_zct_mod/common/slot_targets/$_zct_rom.list" "$_zct_mod/common/slot_targets/aosp.list"; do
        [ -f "$_zct_list" ] || continue
        while IFS= read -r _zct_line || [ -n "$_zct_line" ]; do
            _zct_line=$(_zcsf_trim "$_zct_line")
            case "$_zct_line" in
                ''|'#'*) continue ;;
                '['*']') continue ;;
            esac
            # 去掉路径前缀，只要文件名；拒绝路径/非法字符
            case "$_zct_line" in
                */*|*..*|*[!A-Za-z0-9._+-]*) continue ;;
            esac
            echo "$_zct_line"
        done < "$_zct_list"
    done | sort -u
}

# ---------------------------------------------------------------------------
# 目录扫描（未过滤）：本机所有真实字体文件，用于"文件是否存在"判定
# ---------------------------------------------------------------------------
_zcsf_dir_names_all() {
    for _zcd_d in $(_zcsf_font_dirs); do
        for _zcd_f in "$_zcd_d"/*.ttf "$_zcd_d"/*.otf "$_zcd_d"/*.ttc \
                     "$_zcd_d"/*.TTF "$_zcd_d"/*.OTF "$_zcd_d"/*.TTC; do
            [ -f "$_zcd_f" ] || continue
            echo "${_zcd_f##*/}"
        done
    done | sort -u
}

# 目录扫描（对齐 custom-font-switcher 的 slot_dir_names_raw）：
#   · 只收 ≥32KB 的字体（过滤掉罕见古代文字的小字体，避免缺字方块）
#   · sans-serif 系列优先保留；serif/mono/cursive/casual/clock/math/music/emoji/symbol 跳过
#   · 只收 .ttf/.otf（.ttc 合集走单独合成路径，不进单文件槽位）
# ---------------------------------------------------------------------------
_zcsf_dir_names() {
    for _zcd_d in $(_zcsf_font_dirs); do
        for _zcd_f in "$_zcd_d"/*.ttf "$_zcd_d"/*.otf "$_zcd_d"/*.TTF "$_zcd_d"/*.OTF; do
            [ -f "$_zcd_f" ] || continue
            _zcd_sz=$(stat -c %s "$_zcd_f" 2>/dev/null)
            case "$_zcd_sz" in ''|*[!0-9]*) continue ;; esac
            [ "$_zcd_sz" -ge 32768 ] || continue
            _zcd_nm=${_zcd_f##*/}
            _zcd_low=$(_zcsf_lc "$_zcd_nm")
            case "$_zcd_low" in
                *sans-serif*|*sansserif*) echo "$_zcd_nm"; continue ;;
            esac
            case "$_zcd_low" in
                *emoji*|*symbol*|*mono*|*cursive*|*casual*|*math*|*music*|*clock*|*serif*) continue ;;
            esac
            case "$_zcd_low" in *.ttf|*.otf) echo "$_zcd_nm" ;; esac
        done
    done | sort -u
}

# ---------------------------------------------------------------------------
# 永不替换名单：protect.list + 粗细调节族（supportWeightAdjustment 引用的字体）
# 结果写进 _zcsf_protect（小写、空格分隔，一行一个）
# ---------------------------------------------------------------------------
_zcsf_build_protect() {
    _zcp_mod="$1"
    _zcp_tmp=''
    # ① protect.list（显式点名"任何路径都不许替换"）
    if [ -f "$_zcp_mod/common/slot_targets/protect.list" ]; then
        _zcp_tmp=$(sed -n 's/^[[:space:]]*//; s/[[:space:]]*$//; /^#/d; /^\[/d; /^$/d; p' \
            "$_zcp_mod/common/slot_targets/protect.list" 2>/dev/null | tr 'A-Z' 'a-z')
    fi
    # ② 粗细调节族：fonts.xml 里 <family ... supportWeightAdjustment="true"> 引用的字体
    #   换掉这类字体会让系统字重滑块失灵，个别 ROM 还会 SystemUI 异常 → 一律不碰。
    for _zcp_x in $(_zcsf_xml_files); do
        [ -f "$_zcp_x" ] || continue
        _zcp_vadj=$(awk '
            /<family/ { infam = 1; isadj = 0; isid = 0
                        if ($0 ~ /supportWeightAdjustment="true"/) isadj = 1
                        if ($0 ~ /name="/ || $0 ~ /lang="/) isid = 1 }
            infam {
                if (isadj && isid) {
                    line = $0
                    while (match(line, /[A-Za-z0-9_.-]+[.](ttf|otf|ttc|TTF|OTF|TTC)/)) {
                        s = substr(line, RSTART, RLENGTH)
                        print s
                        line = substr(line, RSTART + RLENGTH)
                    }
                }
                if ($0 ~ /<\/family>/) infam = 0
            }' "$_zcp_x" 2>/dev/null | tr 'A-Z' 'a-z')
        [ -n "$_zcp_vadj" ] && _zcp_tmp="$_zcp_tmp
$_zcp_vadj"
    done
    _zcsf_protect=$(printf '%s\n' "$_zcp_tmp" | sed '/^[[:space:]]*$/d' | sort -u)
    printf '%s\n' "$_zcsf_protect"
}

# ---------------------------------------------------------------------------
# 字重提取（对齐 ziyu 的按字重选源文件命名约定）
# ---------------------------------------------------------------------------
_zcsf_weight() {
    case "$1" in
        *ExtraBold*|*Black*|*Heavy*) echo heavy ;;
        *SemiBold*|*Semibold*|*Semi*Bold*) echo semibold ;;
        *Bold*) echo bold ;;
        *Medium*) echo medium ;;
        *Light*) echo light ;;
        *Thin*) echo thin ;;
        *) echo regular ;;
    esac
}

# ---------------------------------------------------------------------------
# 角色分类：输入已小写文件名，输出分类写进 _zcsf_cat
# ---------------------------------------------------------------------------
_zcsf_classify() {
    _zcsf_n="$1"
    _zcsf_cat=latin
    # 保护名单（protect.list + 粗细调节族）
    case "$_zcsf_protect" in *" $_zcsf_n "*) _zcsf_cat=skip; return 0 ;; esac
    # emoji/符号/彩色/图标 —— 永不替换（换掉会缺图标/变方框）
    case "$_zcsf_n" in
        *emoji*|*symbol*|*icon*|*color*|*flag*|*dingbat*|*material*|*music*|*braille*|*math*|*lottie*)
            _zcsf_cat=skip; return 0 ;;
    esac
    # 兜底字体 —— 永不替换（最后一道字形回退）
    case "$_zcsf_n" in *fallback*) _zcsf_cat=skip; return 0 ;; esac
    # 时钟/数字
    case "$_zcsf_n" in
        *clock*|*digit*|*dinpro*|*osans-solid*|*number*|*num[-_.]*)
            _zcsf_cat=digit; return 0 ;;
    esac
    # 中文（用精确词，避免 "GothicSC" 的 SC、"Kaithi" 的 kai 等子串误匹配）
    case "$_zcsf_n" in
        *hans*|*hant*|*cjk*|*chinese*|*tcvf*|*heiti*|*songti*|*kaiti*|*fangsong*|*misanssc*|*misanstc*|*traditional*|*big5*|*sourcehan*|*notosanssc*|*notoserifsc*|*notosanstc*|*notoseriftc*|*notosanscjk*|*notoserifcjk*|*harmony*)
            _zcsf_cat=cjk; return 0 ;;
    esac
    # 日文/韩文
    case "$_zcsf_n" in
        *korean*|*hangul*|*kore[-_.]*|*japanese*|*kana*|*japan[-_.]*|*jpan*|*jp[-_.]*|*kr[-_.]*)
            _zcsf_cat=lang; return 0 ;;
    esac
    # 其它文字（各语种 Noto / 阿拉伯 / 希伯来 / 泰 / 天城 / 缅 / 藏 …）
    case "$_zcsf_n" in
        *arab*|*hebr*|*thai*|*deva*|*beng*|*taml*|*telu*|*gujr*|*guru*|*knda*|*mlym*|*orya*|*odia*|*sinh*|*khmr*|*khmer*|*laoo*|*lao[-_.]*|*mymr*|*myanmar*|*geor*|*armn*|*ethi*|*tibt*|*tibetan*|*cherokee*|*naskh*|*nastaliq*|*kufi*|*nko*|*yi[-_.]*|*tifinagh*|*adlam*|*bamum*|*vai*|*syriac*|*mongolian*|*javanese*|*balinese*|*lepcha*|*grantha*|*modi*|*newa*|*kaithi*|*bhaiksuki*|*marchen*|*soyombo*|*medefaidrin*|*anatolian*|*cuneiform*|*egyptian*|*linear*|*coptic*|*ogham*|*runic*|*tai[-_.]*|*tamil*|*telugu*|*kannada*|*malayalam*|*gujarati*|*bengali*|*devanagari*|*sinhala*|*ethiopic*|*georgian*|*armenian*|*arabic*|*hebrew*)
            _zcsf_cat=lang; return 0 ;;
    esac
    _zcsf_cat=latin
    return 0
}

# ---------------------------------------------------------------------------
# 主入口：ziyu_cfs_discover <模块目录>
#   生成/刷新 config/device-slots.list（格式与 device_slot_discovery.py 一致，classifier=3）
#   0=成功（缓存已是最新，或本次已重建）；1=失败（调用方回退 Python 版）
# ---------------------------------------------------------------------------
ziyu_cfs_discover() {
    _zcd_mod="${1:-${MODDIR:-}}"
    [ -n "$_zcd_mod" ] && [ -d "$_zcd_mod" ] || return 1
    _zcd_out="$_zcd_mod/config/device-slots.list"
    _zcd_ver=$(sed -n 's/^versionCode=//p' "$_zcd_mod/module.prop" 2>/dev/null | head -n1 | tr -d '\r\n')

    # ---- 指纹：模块版本 + 本脚本 mtime + targets mtime + XML mtime ----
    _zcd_rom=$(_zcsf_rom "$_zcd_mod")
    _zcd_self="$_zcd_mod/common/slot_discovery_cfs.sh"
    _zcd_key="cfs3|$_zcd_ver|$(stat -c '%Y' "$_zcd_self" 2>/dev/null)"
    _zcd_tlist="$_zcd_mod/common/slot_targets/$_zcd_rom.list"
    [ -s "$_zcd_tlist" ] && _zcd_key="$_zcd_key|$(stat -c '%Y-%s' "$_zcd_tlist" 2>/dev/null)"
    for _zcd_x in $(_zcsf_xml_files); do
        [ -f "$_zcd_x" ] && _zcd_key="$_zcd_key|$(stat -c '%Y-%s' "$_zcd_x" 2>/dev/null)"
    done
    if [ -s "$_zcd_out" ]; then
        if [ "$(sed -n 's/^fingerprint=//p' "$_zcd_out" 2>/dev/null | head -n1)" = "$_zcd_key" ]; then
            return 0
        fi
    fi

    # ---- 真实字体文件（未过滤目录扫描）→ 用于过滤 XML/清单里本机不存在的名字 ----
    _zcd_real=" $( _zcsf_dir_names_all 2>/dev/null | sed 's/\r$//' | tr 'A-Z' 'a-z' | sort -u | tr '\n' ' ') "
    # ---- 收集候选文件名（XML + 清单 + 目录，与 slot_device_scan 同源）----
    _zcd_tmp="${TMPDIR:-/data/local/tmp}/.ziyu.cfs.slots.$$"
    {
        _zcsf_xml_names       2>/dev/null
        _zcsf_targets_names "$_zcd_mod" "$_zcd_rom" 2>/dev/null
        _zcsf_dir_names       2>/dev/null
    } | sed 's/\r$//' | tr 'A-Z' 'a-z' | grep -v '^[[:space:]]*$' | sort -u > "$_zcd_tmp" 2>/dev/null
    [ -s "$_zcd_tmp" ] || { rm -f "$_zcd_tmp" 2>/dev/null; return 1; }

    # ---- 建保护名单（空格包裹，分类时用 *" 名字 "* 精确匹配）----
    _zcsf_protect=" $(printf '%s\n' "$(_zcsf_build_protect "$_zcd_mod")" | sed 's/\r$//' | sort -u | tr '\n' ' ') "

    # ---- 分类并输出 ----
    _zcd_tmp2="${_zcd_tmp}.cat"
    : > "$_zcd_tmp2" 2>/dev/null
    _zcd_total=0; _zcd_cjk=0; _zcd_digit=0; _zcd_latin=0; _zcd_lang=0
    while IFS= read -r _zcd_name || [ -n "$_zcd_name" ]; do
        [ -n "$_zcd_name" ] || continue
        case "$_zcd_name" in
            ''|*/*|*..*|*[!a-z0-9._+-]*) continue ;;
        esac
        # 只保留本机真实存在的字体文件（对齐 slot_device_scan 的 [ -f ] 判定）
        case "$_zcd_real" in *" $_zcd_name "*) ;; *) continue ;; esac
        # .ttc 合集走单独合成路径，不进单文件槽位
        case "$_zcd_name" in *.ttc) continue ;; esac
        _zcsf_classify "$_zcd_name"
        case "$_zcsf_cat" in
            skip) continue ;;
            cjk)   _zcd_cjk=$((_zcd_cjk + 1)) ;;
            digit) _zcd_digit=$((_zcd_digit + 1)) ;;
            lang)  _zcd_lang=$((_zcd_lang + 1)) ;;
            *)     _zcd_latin=$((_zcd_latin + 1)) ;;
        esac
        _zcd_total=$((_zcd_total + 1))
        _zcd_w=$(_zcsf_weight "$_zcd_name")
        printf 'slot=%s|%s|%s|-\n' "$_zcd_name" "$_zcsf_cat" "$_zcd_w" >> "$_zcd_tmp2" 2>/dev/null
    done < "$_zcd_tmp"

    [ "$_zcd_total" -gt 0 ] 2>/dev/null || { rm -f "$_zcd_tmp" "$_zcd_tmp2" 2>/dev/null; return 1; }

    # ---- 写缓存（tmp + mv，避免半成品）----
    mkdir -p "$_zcd_mod/config" 2>/dev/null
    _zcd_out_tmp="$_zcd_out.tmp.$$"
    {
        printf 'schema=ziyu-device-slots-v1\n'
        printf 'classifier=3\n'
        printf 'module_version=%s\n' "$_zcd_ver"
        printf 'xml_roots=%s\n' "$(_zcsf_xml_files | grep -c .)"
        printf 'fingerprint=%s\n' "$_zcd_key"
        cat "$_zcd_tmp2" 2>/dev/null
        printf 'count_total=%s\n' "$_zcd_total"
        printf 'count_cjk=%s\n' "$_zcd_cjk"
        printf 'count_digit=%s\n' "$_zcd_digit"
        printf 'count_latin=%s\n' "$_zcd_latin"
        printf 'count_lang=%s\n' "$_zcd_lang"
    } > "$_zcd_out_tmp" 2>/dev/null
    if [ -s "$_zcd_out_tmp" ]; then
        mv -f "$_zcd_out_tmp" "$_zcd_out" 2>/dev/null
    fi
    rm -f "$_zcd_tmp" "$_zcd_tmp2" "$_zcd_out_tmp" 2>/dev/null
    [ -s "$_zcd_out" ] && return 0 || return 1
}
