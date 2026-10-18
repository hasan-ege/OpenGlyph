#!/bin/sh
# Compiles the Android-free core and runs the host tests with a plain JDK.
set -e
here=$(cd "$(dirname "$0")/.." && pwd)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac -d "$out" \
    "$here"/src/org/openglyph/core/*.java \
    "$here"/src/org/openglyph/effects/*.java \
    "$here"/src/org/openglyph/toys/*.java \
    "$here"/tests/org/openglyph/core/*.java
java -cp "$out" org.openglyph.core.HostTests
