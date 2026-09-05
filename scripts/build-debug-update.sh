#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

echo "Running TimeHUD unit tests..."
./gradlew --gradle-user-home .gradle-work testDebugUnitTest

echo "Building a fresh debug-signed TimeHUD APK..."
./gradlew --gradle-user-home .gradle-work assembleDebug --rerun-tasks

version_name="$(
    sed -nE \
        's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' \
        app/build.gradle.kts |
        head -n 1
)"

if [[ -z "$version_name" ]]; then
    echo "Could not read versionName from app/build.gradle.kts." >&2
    exit 1
fi

source_apk="app/build/outputs/apk/debug/app-debug.apk"
destination_dir="app/release"
destination_apk="$destination_dir/TimeHUD-v${version_name}-debug-update.apk"
phone_target="u0_a320@100.124.117.126"
phone_port="8022"
phone_destination="storage/downloads/$(basename "$destination_apk")"

if [[ ! -s "$source_apk" ]]; then
    echo "Expected debug APK was not created at $source_apk." >&2
    exit 1
fi

mkdir -p "$destination_dir"
cp "$source_apk" "$destination_apk"

echo
echo "Debug update APK created:"
echo "$repo_root/$destination_apk"
sha256sum "$destination_apk"

echo
echo "Copying the debug update APK to Android Downloads..."
scp \
    -P "$phone_port" \
    -o BatchMode=yes \
    -o ConnectTimeout=10 \
    "$destination_apk" \
    "$phone_target:$phone_destination"

local_hash="$(sha256sum "$destination_apk" | awk '{print $1}')"
remote_hash="$(
    ssh \
        -p "$phone_port" \
        -o BatchMode=yes \
        -o ConnectTimeout=10 \
        "$phone_target" \
        "sha256sum '$phone_destination'" |
        awk '{print $1}'
)"

if [[ "$local_hash" != "$remote_hash" ]]; then
    echo "APK transfer verification failed: local and phone SHA-256 differ." >&2
    exit 1
fi

echo "APK copied to Android Downloads and SHA-256 verified:"
echo "$remote_hash"
