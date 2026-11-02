#!/bin/sh
# Shared build-time version helpers. module.prop is the single source of truth.

version_prop_get() {
    _vp_key="$1"
    _vp_root="${ROOT:-${GITHUB_WORKSPACE:-$(pwd)}}"
    [ -f "$_vp_root/module.prop" ] || {
        echo "module.prop not found under $_vp_root" >&2
        return 1
    }
    sed -n "s/^${_vp_key}=//p" "$_vp_root/module.prop" | head -n1
}

ZIYU_VERSION=$(version_prop_get version)
ZIYU_VERSION_CODE=$(version_prop_get versionCode)
ZIYU_ARTIFACT_VERSION=$(printf '%s' "$ZIYU_VERSION" | sed 's#[ /]#-#g')
case "$(version_prop_get versionSeries)" in
    ziyu) ZIYU_APP_VERSION_CODE="$ZIYU_VERSION_CODE" ;;
    *) ZIYU_APP_VERSION_CODE=$((ZIYU_VERSION_CODE * 100 + 1)) ;;
esac

ZIYU_RELEASE_TAG=$(python3 "${ROOT:-${GITHUB_WORKSPACE:-$(pwd)}}/scripts/release_version_policy.py" --module "${ROOT:-${GITHUB_WORKSPACE:-$(pwd)}}/module.prop" --field tag --check) || return 1
ZIYU_RELEASE_NOTES="更新日志/RELEASE_NOTES_${ZIYU_RELEASE_TAG}.md"
ZIYU_RELEASE_TITLE=$(python3 "${ROOT:-${GITHUB_WORKSPACE:-$(pwd)}}/scripts/release_version_policy.py" --module "${ROOT:-${GITHUB_WORKSPACE:-$(pwd)}}/module.prop" --field title) || return 1

export ZIYU_VERSION ZIYU_VERSION_CODE ZIYU_ARTIFACT_VERSION ZIYU_APP_VERSION_CODE
export ZIYU_RELEASE_TAG ZIYU_RELEASE_NOTES ZIYU_RELEASE_TITLE
