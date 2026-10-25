#!/system/bin/sh
_zsd_module="$1"; shift
. "$_zsd_module/common/user_storage.sh" || exit 1
ziyu_user_storage_command "$_zsd_module" dispatch "$@"
