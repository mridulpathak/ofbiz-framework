#!/bin/sh
# PROTOTYPE B (measurement only, not wired into anything): fetch only the needed bytes of the
# Gradle distribution with HTTP range requests and verify the nested gradle-wrapper.jar against
# the published wrapper-jar checksum. Needs a JDK (11+), which OFBiz requires anyway.
# Usage: proto-wrapper-b.sh <gradle-version> <output-dir>
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
java "$HERE/ProtoWrapperJarFetcher.java" "$1" "$2"
