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
