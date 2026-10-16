#!/usr/bin/env bash
# Offline replay of the module's SymbolSearch with a host DexKit build (run in WSL / Linux).
#   WORK: build directory with jdk/, build/libdexkit.so (DexKit 2.3.0 host build) and libs/
#   replay.sh <symbols.json> <out-dir> <section>=<apk> ...
set -euo pipefail
WORK=${WORK:-$HOME/dkbuild}
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$(cd "$HERE/../../../app/src/main/java" && pwd)
LIBS=$WORK/libs
mkdir -p "$LIBS"
fetch() { [ -f "$LIBS/$(basename "$1")" ] || curl -sfL -o "$LIBS/$(basename "$1")" "https://repo1.maven.org/maven2/$1"; }
fetch org/luckypray/dexkit/2.3.0/dexkit-2.3.0.aar
fetch com/google/flatbuffers/flatbuffers-java/23.5.26/flatbuffers-java-23.5.26.jar
fetch org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar
fetch org/json/json/20240303/json-20240303.jar
[ -f "$LIBS/dexkit-2.3.0.jar" ] || (cd "$LIBS" && unzip -o -q dexkit-2.3.0.aar classes.jar && mv classes.jar dexkit-2.3.0.jar)
CP="$LIBS/dexkit-2.3.0.jar:$LIBS/flatbuffers-java-23.5.26.jar:$LIBS/kotlin-stdlib-1.9.24.jar:$LIBS/json-20240303.jar"
OUT=$WORK/replay-classes
rm -rf "$OUT" && mkdir -p "$OUT"
"$WORK/jdk/bin/javac" -nowarn -d "$OUT" -cp "$CP" "$SRC/local/jiege/hook/common/SymbolSearch.java" "$HERE/ReplayMain.java"
SPEC=$1; DEST=$2; shift 2
mkdir -p "$DEST"
for job in "$@"; do
  section=${job%%=*}; apk=${job#*=}
  name=$(basename "$apk" .apk)
  "$WORK/jdk/bin/java" -Djava.library.path="$WORK/build" -cp "$OUT:$CP" ReplayMain "$SPEC" "$section" "$apk" "$DEST/$section-$name.json"
done
