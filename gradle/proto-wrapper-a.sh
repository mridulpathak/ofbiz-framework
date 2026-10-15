#!/bin/sh
# PROTOTYPE A (measurement only, not wired into anything): download the full Gradle
# distribution, verify it against its published checksum, extract the nested
# gradle-wrapper.jar and verify that against the published wrapper-jar checksum.
# Usage: proto-wrapper-a.sh <gradle-version> <output-dir>
set -e

V="$1"
OUT="$2"
BASE="https://services.gradle.org/distributions"

mkdir -p "$OUT"

curl -fsSL -o "$OUT/bin.zip" "$BASE/gradle-$V-bin.zip"

EXPECTED_ZIP=$(curl -fsSL "$BASE/gradle-$V-bin.zip.sha256")
ACTUAL_ZIP=$(shasum -a 256 "$OUT/bin.zip" | cut -d' ' -f1)
if [ "$EXPECTED_ZIP" != "$ACTUAL_ZIP" ]; then
    echo "distribution checksum mismatch: expected $EXPECTED_ZIP actual $ACTUAL_ZIP"
    exit 1
fi

unzip -q -j -o "$OUT/bin.zip" "gradle-$V/lib/plugins/gradle-wrapper-main-$V.jar" -d "$OUT"
unzip -q -j -o "$OUT/gradle-wrapper-main-$V.jar" gradle-wrapper.jar -d "$OUT"

EXPECTED=$(curl -fsSL "$BASE/gradle-$V-wrapper.jar.sha256")
ACTUAL=$(shasum -a 256 "$OUT/gradle-wrapper.jar" | cut -d' ' -f1)
if [ "$EXPECTED" = "$ACTUAL" ]; then
    echo "A: wrapper jar OK ($ACTUAL)"
else
    echo "A: wrapper jar MISMATCH expected $EXPECTED actual $ACTUAL"
    exit 1
fi
