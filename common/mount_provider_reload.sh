#!/system/bin/sh
# Provider soft-reload adapter. Publication and mounting still belong to Mountify.
# Mutations run only in KernelSU's managed cleanup stage, in PID 1's namespace.

_zpr_plan() {
    mkdir -p "$_zpr_module/.ziyu-state" || return 1
    _zpr_plan_file="$_zpr_module/.ziyu-state/provider-reload.plan"
    # Ownership comes from the active provider, its installed script and its
    # unique lowerdir. Boot-completed deletes Mountify's /dev logs, so they
    # cannot be the ownership authority for a later soft reboot.
    awk -v name="$_zpr_name" '
        { id=$1; parent[id]=$2; target[id]=$5; line[id]=$0
          for (i=7;i<=NF;i++) if ($i=="-") { fs[id]=$(i+1); break }
          provider=(index($0,"lowerdir=/mnt/vendor/" name "/") || index($0,"lowerdir=/mnt/" name "/"))
          if (provider && (fs[id]!="overlay" || $4!="/")) bad=1
          own[id]=(provider && fs[id]=="overlay" && $4=="/") }
        END {
          for (id in own) if (own[id]) {
            if (target[id] !~ /^\/(system|system_ext|product|vendor|odm|apex|mi_ext|my_[a-z_]+|oem|optics|prism)\/[A-Za-z0-9_.\/-]+$/ ||
                target[id] ~ /\/\.\.?($|\/)/) bad=1
            if (++count>256) bad=1
          }
          for (id in parent) {
            p=parent[id]; depth=0
            while (p in parent && p!=parent[p] && depth++<512) {
              if (!own[id] && own[p]) bad=1
              p=parent[p]
            }
            if (depth>=512) bad=1
            if (own[id]) output[id]=depth "|" id "|" target[id]
          }
          if (bad) exit 1
          for (id in output) print output[id]
        }' /proc/1/mountinfo > "$_zpr_plan_file.unsorted" || return 1
    sort -t '|' -k1,1nr "$_zpr_plan_file.unsorted" > "$_zpr_plan_file" || return 1
    rm -f "$_zpr_plan_file.unsorted"
}

_zpr_cleanup() {
    # No module-scoped unload is claimed. This is the whole provider's managed
    # reload boundary; KernelSU runs metamount.sh again after all module hooks.
    _zpr_init_ns=$(readlink /proc/1/ns/mnt)
    [ -n "$_zpr_init_ns" ] && [ "$(readlink /proc/self/ns/mnt)" = "$_zpr_init_ns" ] || {
        PROVIDER_RELOAD_REASON=provider-cleanup-needs-pid1-namespace; return 1;
    }
    _zpr_plan || { PROVIDER_RELOAD_REASON=provider-layer-ownership-unconfirmed; return 1; }
    [ ! -s "$_zpr_plan_file" ] && [ "$_zpr_lock" = 0 ] && return 0
    _zpr_counter=$(sed -n 's/^BOOTCOUNT=//p' "$_zpr_dir/count.sh" 2>/dev/null | head -n1)
    case "$_zpr_counter" in ''|0) ;; *)
        PROVIDER_RELOAD_REASON=mountify-boot-cycle-not-finished; return 1 ;; esac
    _zpr_lock_dir="$_zpr_module/.ziyu-state/provider-reload.lock"
    . "$_zpr_module/common/font_switch_lock.sh" || return 1
    ziyu_font_lock_acquire "$_zpr_lock_dir" "$$" || { PROVIDER_RELOAD_REASON=provider-reload-busy; return 1; }
    cp "$_zpr_plan_file" "$_zpr_module/.ziyu-state/provider-reload.started" || {
        ziyu_font_lock_release "$_zpr_lock_dir" "$$"; return 1;
    }
    # Match the installed provider's exact single-instance protocol. Keep its
    # anti-bootloop counter intact, and don't run the provider's mount script here.
    touch /dev/mountify_single_instance || { ziyu_font_lock_release "$_zpr_lock_dir" "$$"; return 1; }
    _zpr_cleanup_rc=0
    _zpr_attempt=0
    while [ "$_zpr_attempt" -lt 256 ]; do
        _zpr_plan || { _zpr_cleanup_rc=1; break; }
        [ -s "$_zpr_plan_file" ] || break
        IFS='|' read -r _zpr_depth _zpr_id _zpr_target < "$_zpr_plan_file"
        printf '[PROVIDER-RELOAD] removing Mountify layer id=%s target=%s\n' "$_zpr_id" "$_zpr_target" \
            >> "$_zpr_module/logs/mount-backend.log"
        if [ -x /data/adb/ksu/bin/busybox ]; then
            /data/adb/ksu/bin/busybox umount -l "$_zpr_target"
        else
            umount -l "$_zpr_target"
        fi
        if [ "$?" -ne 0 ] || awk -v id="$_zpr_id" '$1==id { found=1 } END { exit !found }' /proc/1/mountinfo; then
            _zpr_cleanup_rc=1; break
        fi
        _zpr_attempt=$((_zpr_attempt + 1))
    done
    if [ "$_zpr_cleanup_rc" = 0 ] && _zpr_plan && [ ! -s "$_zpr_plan_file" ]; then
        rm -f /dev/mountify_single_instance || _zpr_cleanup_rc=1
    else
        _zpr_cleanup_rc=1
    fi
    ziyu_font_lock_release "$_zpr_lock_dir" "$$" 2>/dev/null || true
    if [ "$_zpr_cleanup_rc" != 0 ]; then
        PROVIDER_RELOAD_REASON=mountify-cleanup-incomplete
        return 1
    fi
    _zpr_lock=0; _zpr_layers=0
    printf '[PROVIDER-RELOAD] old Mountify layers removed; KernelSU will republish and remount\n' \
        >> "$_zpr_module/logs/mount-backend.log"
}

ziyu_provider_reload_check() {
    _zpr_module="$1"
    _zpr_phase="${2:-request}"
    PROVIDER_RELOAD_REASON=none
    PROVIDER_RELOAD_MESSAGE=''
    _zpr_dir=$(readlink -f /data/adb/metamodule 2>/dev/null)
    _zpr_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')
    _zpr_previous="$_zpr_module/config/mount-backend.conf"
    case "$_zpr_dir" in /data/adb/modules/*) ;; *) _zpr_dir='' ;; esac
    if [ "$(sed -n 's/^id=//p' "$_zpr_dir/module.prop" 2>/dev/null | head -n1 | tr -d '\r')" != mountify ]; then
        # A newly selected provider cannot prove an older Mountify layer was removed.
        [ "$(sed -n 's/^boot_id=//p' "$_zpr_previous" 2>/dev/null | head -n1)" = "$_zpr_boot" ] || return 0
        [ "$(sed -n 's/^provider_id=//p' "$_zpr_previous" 2>/dev/null | head -n1)" = mountify ] || return 0
        _zpr_dir=$(sed -n 's/^meta_module_dir=//p' "$_zpr_previous" 2>/dev/null | head -n1)
        case "$_zpr_dir" in /data/adb/modules/*) ;; *) return 0 ;; esac
        [ "$(sed -n 's/^id=//p' "$_zpr_dir/module.prop" 2>/dev/null | head -n1 | tr -d '\r')" = mountify ] || return 0
    fi
    case "$_zpr_dir" in *'/../'*|*'/./'*|*/..|*/.) return 1 ;; esac
    _zpr_lock=0
    # Match the installed implementation rather than assuming all Mountify versions behave alike.
    if grep -Fq '/dev/mountify_single_instance' "$_zpr_dir/metamount.sh" 2>/dev/null && \
       [ -e /proc/1/root/dev/mountify_single_instance ]; then
        _zpr_lock=1
    fi
    _zpr_hook=0
    # This file belongs to the Mountify metamodule itself, not to Ziyu. Read it
    # as provider metadata only; Ziyu no longer runs a late/soft-reboot stage.
    [ ! -s "$_zpr_dir/emulated-soft-reboot.sh" ] || _zpr_hook=1
    _zpr_name=$(sed -n 's/^[[:space:]]*FAKE_MOUNT_NAME=["\047]*\([A-Za-z0-9_.-]*\).*/\1/p' \
        /data/adb/mountify/config.sh 2>/dev/null | tail -n1)
    [ -n "$_zpr_name" ] || _zpr_name=mountify
    _zpr_known=0
    if grep -Fq '/dev/mountify_single_instance' "$_zpr_dir/metamount.sh" 2>/dev/null && \
       grep -Fq 'FAKE_MOUNT_NAME="mountify"' "$_zpr_dir/metamount.sh" 2>/dev/null; then
        _zpr_known=1
    fi
    _zpr_layers=$(awk -v name="$_zpr_name" '$5 ~ /\/fonts(\/|$)/ && \
        (index($0, "lowerdir=/mnt/vendor/" name "/") || index($0, "lowerdir=/mnt/" name "/")) \
        { count++ } END { print count+0 }' /proc/1/mountinfo 2>/dev/null)
    case "$_zpr_layers" in ''|*[!0-9]*)
        PROVIDER_RELOAD_REASON=provider-font-mount-state-unreadable
        PROVIDER_RELOAD_MESSAGE='无法确认元模块旧字体挂载是否已清理，已保留待应用字体。'
        return 1 ;;
    esac
    _zpr_preference=$(sed -n 's/^preferred_backend=//p' "$_zpr_module/config/mount-backend-preference.conf" 2>/dev/null | head -n1)
    _zpr_font=$(sed -n 's/^font=//p' "$_zpr_module/config/font-payload-next.conf" 2>/dev/null | head -n1)
    [ -n "$_zpr_font" ] || _zpr_font=$(sed -n 's/^font=//p' "$_zpr_module/config/universal-font-next.conf" 2>/dev/null | head -n1)
    [ -n "$_zpr_font" ] || _zpr_font=$(head -n1 "$_zpr_module/config/active_font.conf" 2>/dev/null | tr -d '\r')
    _zpr_needed=1
    if [ "$_zpr_layers" = 0 ]; then
        case "$_zpr_preference" in magic|overlayfs|self_mount) _zpr_needed=0 ;; esac
        [ "$_zpr_font" != default ] || _zpr_needed=0
    fi
    _zpr_result=ready
    _zpr_owns_self=0
    if [ "$(sed -n 's/^boot_id=//p' "$_zpr_previous" 2>/dev/null | head -n1)" = "$_zpr_boot" ] && \
       [ "$(sed -n 's/^active_backend=//p' "$_zpr_previous" 2>/dev/null | head -n1)" = self ]; then
        _zpr_owns_self=1
    fi
    if [ "$_zpr_needed" = 1 ] && { [ "$_zpr_lock" = 1 ] || [ "$_zpr_layers" -gt 0 ]; }; then
        if [ "$_zpr_phase" = request ] && { [ "$_zpr_hook" = 1 ] || \
           { [ "$_zpr_known" = 1 ] && { _zpr_plan || [ "$_zpr_owns_self" = 1 ]; }; }; }; then
            _zpr_result=cleanup-required
        elif [ "$_zpr_phase" = reload ] && [ "$_zpr_known" = 1 ] && _zpr_cleanup; then
            _zpr_result=cleaned
        else
            _zpr_result=blocked
            [ "$PROVIDER_RELOAD_REASON" != none ] || PROVIDER_RELOAD_REASON=mountify-soft-reload-unavailable
            PROVIDER_RELOAD_MESSAGE='Mountify 旧挂载清理未确认，已保留待应用字体。请导出挂载日志。'
        fi
    fi
    mkdir -p "$_zpr_module/config" || return 1
    _zpr_report="$_zpr_module/config/provider-reload-check.conf"
    {
        printf 'schema=ziyu-provider-reload-check-v1\nboot_id=%s\nphase=%s\n' "$_zpr_boot" "$_zpr_phase"
        printf 'provider_id=mountify\nscan_lock=%s\ncleanup_hook=%s\nfont_layers=%s\n' "$_zpr_lock" "$_zpr_hook" "$_zpr_layers"
        printf 'state=%s\nreason=%s\n' "$_zpr_result" "$PROVIDER_RELOAD_REASON"
    } > "$_zpr_report.tmp.$$" && chmod 0600 "$_zpr_report.tmp.$$" && \
        mv -f "$_zpr_report.tmp.$$" "$_zpr_report" || return 1
    [ "$_zpr_result" != blocked ]
}
