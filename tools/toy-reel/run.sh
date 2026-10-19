#!/bin/sh
# Records every toy with the real toy code and writes previews/toys.html (see ToyReel).
#   sh tools/toy-reel/run.sh [out.html]
set -e
here=$(cd "$(dirname "$0")" && pwd)
og=$(cd "$here/../.." && pwd)
out=${1:-$og/../previews/toys.html}
cls=$(mktemp -d)
trap 'rm -rf "$cls"' EXIT
javac -nowarn -d "$cls" \
    "$og"/src/org/openglyph/core/*.java \
    "$og"/src/org/openglyph/effects/*.java \
    "$og"/src/org/openglyph/toys/*.java \
    "$here"/org/openglyph/toys/ToyReel.java
java -cp "$cls" org.openglyph.toys.ToyReel "$out" "$here/template.html"
