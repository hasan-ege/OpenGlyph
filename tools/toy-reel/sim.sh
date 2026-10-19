#!/bin/sh
# Live toy simulator on http://localhost:8766/ (see ToySim). Re-run after changing a toy.
set -e
here=$(cd "$(dirname "$0")" && pwd)
og=$(cd "$here/../.." && pwd)
cls=$(mktemp -d)
trap 'rm -rf "$cls"' EXIT
javac -nowarn -d "$cls" \
    "$og"/src/org/openglyph/core/*.java \
    "$og"/src/org/openglyph/effects/*.java \
    "$og"/src/org/openglyph/toys/*.java \
    "$here"/org/openglyph/toys/ToyReel.java \
    "$here"/org/openglyph/toys/ToySim.java
exec java -cp "$cls" org.openglyph.toys.ToySim "${1:-8766}" "$here/sim.html"
