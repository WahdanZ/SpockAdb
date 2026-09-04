#!/usr/bin/env bash
#
# Gate on the descriptor that is about to be published to JetBrains Marketplace.
#
# Why this exists
# ---------------
# Between 2.0.3 (July 2022) and 4.0.0 the Marketplace served **1.0.2** to every modern
# Android Studio and IntelliJ IDEA. Two independent descriptor mistakes caused it, and
# neither failed a build:
#
#   * 2.0.0-2.0.3 shipped `until-build=211.*`/`213.*`. An until-build cap does not break
#     anything at release time — it silently makes the release invisible to every IDE
#     released after the cap, and the Marketplace falls back to the newest upload without
#     one, which was 1.0.2.
#   * 3.0.0 and 3.0.1 renamed `<id>` from `com.wahdan.com.wahdan.spockAdb` to
#     `com.wahdan.spockAdb`. The plugin id is the Marketplace primary key, so those
#     uploads could never land on listing 11591 at all.
#
# Both are cheap to assert and impossible to notice by reading a green build log, so they
# are asserted here, against the built artifact rather than against the source descriptor
# (since-build, until-build and version are all patched in by the Gradle plugin).
#
# Usage: scripts/verify-marketplace-descriptor.sh [distributions-dir]
#        EXPECTED_VERSION=4.0.0 scripts/verify-marketplace-descriptor.sh

set -euo pipefail

# The id of Marketplace listing 11591 (https://plugins.jetbrains.com/plugin/11591-spockadb).
# Changing it does not create a new version of this plugin — it orphans the release.
readonly MARKETPLACE_PLUGIN_ID="com.wahdan.com.wahdan.spockAdb"

DIST_DIR="${1:-build/distributions}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

fail() { echo "::error::$*" >&2; exit 1; }

ZIP="$(find "$DIST_DIR" -maxdepth 1 -name '*.zip' | head -1)"
[ -n "$ZIP" ] || fail "No plugin distribution found in $DIST_DIR — run ./gradlew buildPlugin first."

unzip -qo "$ZIP" -d "$WORK_DIR/dist"

# The distribution is <plugin>/lib/*.jar; the descriptor lives in whichever jar carries it,
# not necessarily the one whose name matches the plugin.
DESCRIPTOR=""
for jar in "$WORK_DIR"/dist/*/lib/*.jar; do
    if unzip -p "$jar" META-INF/plugin.xml > "$WORK_DIR/plugin.xml" 2>/dev/null; then
        DESCRIPTOR="$WORK_DIR/plugin.xml"
        break
    fi
done
[ -n "$DESCRIPTOR" ] || fail "No META-INF/plugin.xml in any jar inside $(basename "$ZIP")."

# An absent attribute or element is a legitimate result (an open-ended until-build is the
# whole point), so these must return empty rather than a non-zero status under `set -e`.
attr() { grep -o "<idea-version[^>]*" "$DESCRIPTOR" | grep -o "$1=\"[^\"]*\"" | cut -d'"' -f2 || true; }
element() { grep -o "<$1>[^<]*</$1>" "$DESCRIPTOR" | head -1 | sed -E "s|</?$1>||g" || true; }

ACTUAL_ID="$(element id)"
ACTUAL_VERSION="$(element version)"
SINCE_BUILD="$(attr since-build)"
UNTIL_BUILD="$(attr until-build)"
EXPECTED_SINCE="$(grep '^pluginSinceBuild=' gradle.properties | cut -d= -f2)"

echo "Descriptor in $(basename "$ZIP"):"
echo "  id           = ${ACTUAL_ID}"
echo "  version      = ${ACTUAL_VERSION}"
echo "  since-build  = ${SINCE_BUILD}"
echo "  until-build  = ${UNTIL_BUILD:-<none>}"

[ "$ACTUAL_ID" = "$MARKETPLACE_PLUGIN_ID" ] || fail \
    "Plugin id is '${ACTUAL_ID}', expected '${MARKETPLACE_PLUGIN_ID}'. The id is the \
Marketplace primary key: publishing under a different one does not update listing 11591, \
it fails or creates an unrelated listing. This is what stopped 3.0.0 and 3.0.1 shipping."

[ -z "$UNTIL_BUILD" ] || fail \
    "Descriptor declares until-build='${UNTIL_BUILD}'. This release must stay open-ended: \
a cap makes it invisible to every IDE newer than the cap, and the Marketplace silently \
serves an older version instead. Leave pluginUntilBuild empty in gradle.properties."

[ -n "$SINCE_BUILD" ] || fail "Descriptor has no since-build."
[ "$SINCE_BUILD" = "$EXPECTED_SINCE" ] || fail \
    "since-build is '${SINCE_BUILD}' but gradle.properties says pluginSinceBuild=${EXPECTED_SINCE}."

if [ -n "${EXPECTED_VERSION:-}" ]; then
    [ "$ACTUAL_VERSION" = "$EXPECTED_VERSION" ] || fail \
        "Descriptor version is '${ACTUAL_VERSION}' but the release is '${EXPECTED_VERSION}'. \
The release job checks out the tag, so this means gradle.properties was not bumped."
fi

echo "Descriptor is publishable to Marketplace listing 11591."
