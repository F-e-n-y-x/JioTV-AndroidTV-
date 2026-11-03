#!/usr/bin/env bash
# Local beta release, no GitHub Actions: builds the signed APK, pushes the branch and tag, publishes the
# pre-release with .github/BETA_NOTES.md, then deletes the older beta pre-releases (their tags stay).
# Bump versionName/versionCode in app/build.gradle.kts and commit before running.
# DRY_RUN=1 ./release-beta.sh   prints the publish and delete steps without running them.
set -euo pipefail
cd "$(dirname "$0")"

run() { if [ -n "${DRY_RUN:-}" ]; then echo "[dry-run] $*"; else "$@"; fi; }

VER=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
TAG="v$VER"
[[ "$VER" == *-* ]] || { echo "versionName $VER is not a beta (no '-'); this script is for pre-releases" >&2; exit 1; }
[ -f keystore.properties ] || { echo "keystore.properties missing: refusing to publish a debug-signed APK" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Commit or stash your changes first" >&2; exit 1; }
BRANCH=$(git branch --show-current)

./gradlew assembleRelease -q
APK=app/build/outputs/apk/release/app-release.apk
SDK=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}
BT=$(ls -d "${SDK:-/opt/android-sdk}"/build-tools/*/ | sort -V | tail -1)
# Capture first: grep -q closing the pipe early would make apksigner/aapt2 fail under pipefail.
CERTS=$("$BT/apksigner" verify --print-certs "$APK")
[[ "$CERTS" != *"Android Debug"* ]] || { echo "APK is debug-signed" >&2; exit 1; }
BADGING=$("$BT/aapt2" dump badging "$APK")
[[ "$BADGING" == *"versionName='$VER'"* ]] || { echo "APK versionName is not $VER" >&2; exit 1; }

OUT="$(mktemp -d)/JTV-$TAG.apk"
cp "$APK" "$OUT"

run git push origin "$BRANCH"
run git tag "$TAG"
run git push origin "$TAG"
run gh release create "$TAG" "$OUT" --title "JTV $TAG" --notes-file ../.github/BETA_NOTES.md --prerelease --target "$BRANCH"

# One beta at a time: delete the other beta pre-releases (same rule as build-apk.yml). Stable is never touched.
gh release list -L 100 --json tagName,isPrerelease -q '.[] | select(.isPrerelease) | .tagName' |
while read -r t; do
  [ "$t" = "$TAG" ] || run gh release delete "$t" --yes
done
echo "Released $TAG"
