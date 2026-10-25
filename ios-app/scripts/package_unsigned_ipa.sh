#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_APP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BUILD_DIR="${IOS_APP_DIR}/build"
DERIVED_DATA_DIR="${BUILD_DIR}/DerivedData"
STAGE_DIR="${BUILD_DIR}/ipa_stage"
IPA_NAME="ONT_WiFi_Configurator_Unsigned.ipa"
IPA_PATH="${IOS_APP_DIR}/${IPA_NAME}"
SHA_PATH="${IOS_APP_DIR}/SHA256SUMS.txt"

cd "${IOS_APP_DIR}"

if command -v xcodegen >/dev/null 2>&1 && [[ -f "project.yml" ]]; then
    echo "[1/5] Generating Xcode project via xcodegen..."
    xcodegen generate
    sed -i.bak 's/objectVersion = 77;/objectVersion = 56;/g' ONTWiFiConfigurator.xcodeproj/project.pbxproj && rm -f ONTWiFiConfigurator.xcodeproj/project.pbxproj.bak
fi

echo "[2/5] Building Release ONTWiFiConfigurator.app (Unsigned / Ad-hoc)..."
xcodebuild build \
    -project ONTWiFiConfigurator.xcodeproj \
    -scheme ONTWiFiConfigurator \
    -configuration Release \
    -sdk iphoneos \
    -derivedDataPath "${DERIVED_DATA_DIR}" \
    CODE_SIGN_IDENTITY="" \
    CODE_SIGNING_REQUIRED=NO \
    CODE_SIGNING_ALLOWED=NO \
    AD_HOC_CODE_SIGNING_ALLOWED=YES

APP_BUNDLE_PATH="${DERIVED_DATA_DIR}/Build/Products/Release-iphoneos/ONTWiFiConfigurator.app"
if [[ ! -d "${APP_BUNDLE_PATH}" ]]; then
    echo "ERROR: Built app bundle not found at ${APP_BUNDLE_PATH}" >&2
    exit 1
fi

echo "[3/5] Verifying PII exclusion (file-mau.xlsx must NEVER be bundled in production .app)..."
if [[ -f "${APP_BUNDLE_PATH}/file-mau.xlsx" ]] || [[ -f "${APP_BUNDLE_PATH}/Resources/file-mau.xlsx" ]]; then
    echo "SECURITY ERROR: Real customer PII fixture file-mau.xlsx was found in production .app bundle!" >&2
    exit 1
fi

echo "[4/5] Applying ad-hoc Mach-O signature and packaging ${IPA_NAME}..."
rm -rf "${STAGE_DIR}" "${IPA_PATH}" "${SHA_PATH}"
mkdir -p "${STAGE_DIR}/Payload"
cp -R "${APP_BUNDLE_PATH}" "${STAGE_DIR}/Payload/ONTWiFiConfigurator.app"

codesign --force --sign - --timestamp=none "${STAGE_DIR}/Payload/ONTWiFiConfigurator.app"

(
    cd "${STAGE_DIR}"
    zip -qry "${IPA_PATH}" Payload
)

echo "[5/5] Computing SHA256SUMS.txt..."
(
    cd "${IOS_APP_DIR}"
    shasum -a 256 "${IPA_NAME}" > "${SHA_PATH}"
)

echo "SUCCESS: Packaged ${IPA_PATH} and ${SHA_PATH}"
