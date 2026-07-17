#!/usr/bin/env bash
# build-apk.sh — Build Talaria (formerly Apollo) debug APK with auto-versioning
# and deploy to files.ffilt.us with version in the filename.
#
# Usage:  ./build-apk.sh          # build + deploy
#         ./build-apk.sh --no-deploy  # build only
set -euo pipefail

cd "$(dirname "$0")/android"

# Read credentials from local.properties and pass them to Gradle
LOCAL_PROPS="local.properties"
GRADLE_ARGS=()
if [ -f "$LOCAL_PROPS" ]; then
    while IFS='=' read -r key value; do
        case "$key" in
            cfAccessClientId|cfAccessClientSecret|talariaWsUrl)
                GRADLE_ARGS+=("-P${key}=${value}")
                ;;
        esac
    done < <(grep -E '^(cfAccessClientId|cfAccessClientSecret|talariaWsUrl)=' "$LOCAL_PROPS")
fi

echo "=== Building Talaria APK ==="
./gradlew assembleDebug "${GRADLE_ARGS[@]}"

# Read the generated BuildConfig for version info
BUILD_DIR="app/build/generated/source/buildConfig/debug/com/reedsutliff/talaria"
if [ -f "$BUILD_DIR/BuildConfig.java" ]; then
    VERSION_NAME=$(grep 'VERSION_NAME' "$BUILD_DIR/BuildConfig.java" | sed 's/.*"\(.*\)".*/\1/')
    VERSION_CODE=$(grep 'VERSION_CODE' "$BUILD_DIR/BuildConfig.java" | sed 's/.*= \([0-9]*\);.*/\1/')
else
    echo "ERROR: BuildConfig not found at $BUILD_DIR/BuildConfig.java" >&2
    exit 1
fi

APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
FILENAME="talaria-${VERSION_NAME}.apk"
DEST_DIR="/srv/data/files/apk/Talaria"

echo ""
echo "=== Deploying to $DEST_DIR/ ==="
echo "  Version: $VERSION_NAME  (code $VERSION_CODE)"
echo "  APK:     $FILENAME"

mkdir -p "$DEST_DIR"

cp "$APK_SRC" "$DEST_DIR/$FILENAME"
# Remove old builds except the latest 5
ls -1t "$DEST_DIR"/talaria-*.apk 2>/dev/null | tail -n +6 | xargs -r rm -f
# Update latest symlink
ln -sf "$FILENAME" "$DEST_DIR/talaria-latest.apk"

echo "  ✅ $DEST_DIR/$FILENAME  ($(du -h "$DEST_DIR/$FILENAME" | cut -f1))"
echo "  ✅ Symlink: $DEST_DIR/talaria-latest.apk → $FILENAME"
echo ""

# Update OTA version manifest for install.ffilt.us
VERSION_JSON="/srv/data/files/apk/version-com.reedsutliff.talaria.json"
cat > "$VERSION_JSON" <<EOF
{"app":"com.reedsutliff.talaria","version":"$VERSION_CODE","apk":"Talaria/$FILENAME"}
EOF
echo "  ✅ OTA manifest: $VERSION_JSON"
echo "  📲 install.ffilt.us/update?app=com.reedsutliff.talaria&ver=$VERSION_CODE"

echo ""
echo "  URL: https://files.ffilt.us/apk/Talaria/$FILENAME"
echo "  URL: https://files.ffilt.us/apk/Talaria/talaria-latest.apk"
