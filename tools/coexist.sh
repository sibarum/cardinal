#!/bin/sh
#
# Build Cardinal and Pontif, put every jar from both on one classpath, and run
# tools/Coexist.java against it. See that file for what is asserted.
#
#   usage: tools/coexist.sh <path-to-pontif-checkout>
#
set -eu

SRC=${1:?usage: tools/coexist.sh <path-to-pontif-checkout>}
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DEST=$(CDPATH= cd -- "$HERE/.." && pwd)

# Cygwin/MSYS hand Java POSIX paths it cannot read, so every path crossing the
# boundary goes through cygpath where one exists.
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

echo "building cardinal..."
mvn -o -B -q -f "$DEST/pom.xml" package -DskipTests
echo "building pontif..."
mvn -o -B -q -f "$SRC/pom.xml" -pl pontif-parser,pontif-conservation,pontif-receipts -am package -DskipTests

jars=""
cp=""
for j in "$SRC"/pontif-*/target/pontif-*-1.0-SNAPSHOT.jar "$DEST"/cardinal-*/target/cardinal-*-1.0-SNAPSHOT.jar; do
    [ -f "$j" ] || continue
    jars="$jars $j"
    cp="$cp;$(win "$j")"
done
[ -n "$jars" ] || { echo "coexist.sh: no jars found" >&2; exit 1; }

# Pontif's classes reference Truffle, so it has to be resolvable for them to
# link. Cardinal's do not, which is the point.
truffle=$(ls "$HOME"/.m2/repository/org/graalvm/truffle/truffle-api/*/truffle-api-*.jar 2>/dev/null | head -1)
[ -n "$truffle" ] && cp="$(win "$truffle")$cp"

# shellcheck disable=SC2086
java -cp "${cp#;}" "$(win "$HERE/Coexist.java")" $jars
