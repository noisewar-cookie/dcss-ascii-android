#!/bin/bash
#
# setup.sh — Prepare the dcss-ascii-android build tree.
#
# Run from the android-console-patches/ directory.
# Prerequisites: git, perl, python (with PyYAML), g++ (for tilegen)
#
# This script:
#   1. Creates the jni/ junction/symlink
#   2. Copies custom files (Android.mk, Application.mk, libandroid.cc) into the submodule
#   3. Applies patches to upstream files (initfile.cc, main.cc, syscalls.cc)
#   4. Generates all required headers (version, species, jobs, monsters, forms, art, tiles, etc.)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE_DIR="$PROJECT_DIR/android-crawl-console/crawl-ref/source"
PATCHES_DIR="$SCRIPT_DIR"

echo "=== dcss-ascii-android setup ==="
echo "Project: $PROJECT_DIR"
echo "Source:  $SOURCE_DIR"
echo ""

# ── Step 1: Create jni symlink ──
echo "Creating jni → android-crawl-console/crawl-ref/source link..."
cd "$PROJECT_DIR"
if [ ! -e jni ]; then
    if [[ "$OSTYPE" == "msys"* || "$OSTYPE" == "cygwin"* ]]; then
        cmd //c "mklink /J jni android-crawl-console\crawl-ref\source"
    else
        ln -sv android-crawl-console/crawl-ref/source jni
    fi
else
    echo "  jni already exists, skipping."
fi
echo ""

# ── Step 2: Copy custom files into submodule ──
echo "Copying custom files into submodule source..."
cp -v "$PATCHES_DIR/Android.mk" "$SOURCE_DIR/Android.mk"
cp -v "$PATCHES_DIR/Application.mk" "$SOURCE_DIR/Application.mk"
cp -v "$PATCHES_DIR/libandroid.cc" "$SOURCE_DIR/libandroid.cc"
echo ""

# ── Step 3: Apply patches to upstream files ──
echo "Applying patches to upstream files..."
cd "$PROJECT_DIR/android-crawl-console"
for patch in "$PATCHES_DIR"/*.patch; do
    if [ -f "$patch" ]; then
        echo "  Applying $(basename "$patch")..."
        git apply --check "$patch" 2>/dev/null && git apply "$patch" || echo "  (already applied or conflicts — skipping)"
    fi
done
echo ""

# ── Step 3b: Sync help text files into assets/docs ──
# command.cc:_get_help_section() opens every entry in help_files[] and asserts
# on failure. Missing any of these crashes the app when '?' is pressed in-game.
echo "Syncing help text files into assets/docs..."
DOCS_SRC="$PROJECT_DIR/android-crawl-console/crawl-ref/docs"
DOCS_DST="$PROJECT_DIR/assets/docs"
mkdir -p "$DOCS_DST"
for f in crawl_manual.txt aptitudes.txt quickstart.txt macros_guide.txt options_guide.txt; do
    cp -v "$DOCS_SRC/$f" "$DOCS_DST/$f"
done
echo ""

# ── Step 3b2: Sync upstream LICENSE into assets ──
# GPL-2.0+ compliance: ship the license text alongside the binary in the APK.
# Sourced from the submodule so upstream bumps refresh it automatically.
echo "Syncing LICENSE into assets..."
cp -v "$PROJECT_DIR/android-crawl-console/LICENSE" "$PROJECT_DIR/assets/LICENSE.txt"
echo ""

# ── Step 3c: Compute content hash of assets/dat/ ──
# SplashActivity reads this and compares to the installed copy in filesDir.
# If they match, dat/ is not re-extracted on launch — preserving file mtimes
# so DCSS's TextDB freshness check (database.cc:_needs_update) keeps the
# already-built SQLite caches valid. Hashes content + relative path only;
# mtimes/sizes/perms are ignored so identical content always produces the
# same hash regardless of when the tree was checked out.
echo "Computing assets/dat/ content hash..."
DAT_DIR="$PROJECT_DIR/assets/dat"
HASH_FILE="$PROJECT_DIR/assets/dat-hash.txt"
(
    cd "$DAT_DIR"
    find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum
) | sha256sum | awk '{print $1}' > "$HASH_FILE"
echo "  dat-hash.txt: $(cat "$HASH_FILE")"
echo ""

# ── Step 4: Generate headers ──
echo "Generating headers..."
cd "$SOURCE_DIR"

echo "  Version and config headers..."
perl util/gen_ver.pl build.h
perl util/gen-cflg.pl compflag.h "" "" ""

echo "  YAML-based generators (species, jobs, monsters, forms)..."
python util/species-gen.py dat/species/ util/species-gen/ species-data.h aptitudes.h species-groups.h species-type.h
python util/job-gen.py dat/jobs/ util/job-gen/ job-data.h job-groups.h job-type.h
python util/mon-gen.py dat/mons/ util/mon-gen/ mon-data.h
python util/form-gen.py dat/forms/ util/form-gen/ transformation.h form-data.h

echo "  Perl generators (art, monsters, commands, lua, mi-enum)..."
perl util/art-data.pl
perl util/gen-mst.pl
perl util/cmd-name.pl
perl util/gen-luatags.pl
perl util/gen-mi-enum

echo ""
echo "  Building tilegen..."
if [ ! -f tilegen.exe ] && [ ! -f tilegen ]; then
    cd rltiles/tool
    g++ -O0 -o ../../tilegen.exe tile.cc tile_colour.cc tile_list_processor.cc tile_page.cc tilegen.cc -I../../contrib/lua/src
    cd ../..
    echo "  tilegen built."
else
    echo "  tilegen already exists, skipping build."
fi

echo "  Generating tile headers..."
cd rltiles
for tile in main dngn floor wall feat player gui icons; do
    ../tilegen.exe -c "dc-${tile}.txt"
done
cd ..

echo ""
echo "=== Setup complete ==="
echo "You can now build the APK with: ./gradlew assembleDebug"
