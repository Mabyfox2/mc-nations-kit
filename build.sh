#!/bin/sh
# Builds the plugins from source.
#
#   sh build.sh <server folder> [plugin ...]
#
# <server folder> is a Paper 26.2 server that has been started once, so its libraries/ folder
# holds the Paper API. Plugins that a plugin hooks into are read from <server folder>/plugins:
# VaultUnlocked (UNBank, UNBorders, UNMenu), BlueMap (UNBorders), floodgate and EssentialsX (UNMenu).
# With no plugin names, all five are built. Jars land in dist/.
#
# Needs JDK 25 (the Paper 26.2 API is Java 25). Set JAVAC to use a javac that isn't on the PATH.
set -e
cd "$(dirname "$0")"
SERVER="$1"
if [ -z "$SERVER" ] || [ ! -d "$SERVER/libraries" ]; then
  echo "usage: sh build.sh <server folder with libraries/ and plugins/> [plugin ...]" >&2
  exit 1
fi
shift
JAVAC=${JAVAC:-javac}
CP=$( (find "$SERVER/libraries" -name '*.jar'; ls "$SERVER"/plugins/*.jar 2>/dev/null) | tr '\n' ':')
PLUGINS=${*:-UNBank UNBorders UNGraves UNMenu UsefulTrades}
mkdir -p dist
for P in $PLUGINS; do
  VERSION=$(sed -n "s/^version: '\(.*\)'/\1/p" "$P/src/main/resources/plugin.yml")
  OUT="build/$P"
  rm -rf "$OUT" && mkdir -p "$OUT"
  "$JAVAC" --release 21 -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT" $(find "$P/src/main/java" -name '*.java')
  cp "$P"/src/main/resources/*.yml "$OUT/"
  (cd "$OUT" && jar cf "../../dist/$P-$VERSION.jar" .)
  echo "built dist/$P-$VERSION.jar"
done
rm -rf build
