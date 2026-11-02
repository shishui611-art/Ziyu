#!/system/bin/sh
# Read compatibility only. New data is always written with the current identity.
ziyu_legacy_path() {
    _zil_current="$1"
    if [ -e "$_zil_current" ] || [ -L "$_zil_current" ]; then
        printf '%s\n' "$_zil_current"
        return
    fi
    _zil_remaining=${_zil_current#/}; _zil_old=''
    while [ -n "$_zil_remaining" ]; do
        _zil_segment=${_zil_remaining%%/*}
        case "$_zil_remaining" in */*) _zil_remaining=${_zil_remaining#*/} ;; *) _zil_remaining='' ;; esac
        _zil_candidate="$_zil_old/$_zil_segment"
        if [ ! -e "$_zil_candidate" ] && [ ! -L "$_zil_candidate" ]; then
            case "$_zil_segment" in *.ziyu*)
                _zil_legacy_segment=$(printf '%s' "$_zil_segment" | sed 's/\.ziyu/\.luoshu/g')
                _zil_candidate="$_zil_old/$_zil_legacy_segment" ;;
                Ziyu-*) _zil_candidate="$_zil_old/LuoShu-${_zil_segment#Ziyu-}" ;;
                ZiyuSlot-*) _zil_candidate="$_zil_old/LuoShuSlot-${_zil_segment#ZiyuSlot-}" ;;
            esac
        fi
        _zil_old=$_zil_candidate
    done
    if [ -e "$_zil_old" ] || [ -L "$_zil_old" ]; then printf '%s\n' "$_zil_old"
    else printf '%s\n' "$_zil_current"; fi
}

ziyu_legacy_schema() {
    case "$1" in
        luoshu-next-payload-v1) printf '%s' ziyu-next-payload-v1 ;;
        luoshu-next-transaction-v1) printf '%s' ziyu-next-transaction-v1 ;;
        *) printf '%s' "$1" ;;
    esac
}

ziyu_legacy_source_allowed() {
    case "$1" in
        "$2/.luoshu-payload"|"$2/.luoshu-state/cache/live/$3/"generation-*) return 0 ;;
    esac
    return 1
}

ziyu_legacy_work_allowed() {
    case "$1" in /data/adb/luoshu/live-mount/"$2".*) return 0 ;; esac
    return 1
}
