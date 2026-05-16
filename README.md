# Dungeon Crawl Stone Soup ASCII

An unofficial ASCII port of [Dungeon Crawl Stone Soup](https://crawl.develz.org/) for Android.

This is a fork of [michaelbarlow7/dungeon-crawl-android](https://github.com/michaelbarlow7/dungeon-crawl-android), updated from DCSS 0.29.1 to **0.34.1**. Full credit to Michael Barlow for the original Android console port and JNI adapter architecture.

Full disclosure, LLM AI code was used extensively for this project. The context engineering md files were not included in order to keep the repo clean, and because local project setups, tooling, and MCP used can differ dramatically. The goal of this project is to create the closest ASCII console experience of DCSS on mobile, and will make every effort to constrain AI code changes to just the wrapper, and avoid changing the core game source included in the submodule.


## Architecture

This is an **ASCII/console** port — no tiles, no SDL2, no OpenGL. The game renders via Android's built-in terminal text rendering.

- `android-crawl-console/` — Git submodule pointing to [crawl/crawl](https://github.com/crawl/crawl) at tag 0.34.1 (unmodified upstream)
- `android-console-patches/` — Custom files and patches applied on top of the submodule at build time:
  - `libandroid.cc` — JNI console adapter implementing `libconsole.h`
  - `Android.mk` / `Application.mk` — NDK build files
  - `*.patch` — Minimal diffs to upstream files (currently `initfile.cc`, `main.cc`, `message.cc`, `options.h`, `output.cc`, `startup.cc`, `syscalls.cc`)
  - `setup.sh` — Copies custom files, applies patches, generates headers
- `src/com/crawlmb/` — Java/Android UI layer (keyboard, preferences, game activity)
- `assets/` — Bundled crawl data files (dat/, docs/, settings/)


### Prerequisites

- Android SDK with NDK 30.x
- Java 21 (Android Studio JBR recommended)
- Perl (Strawberry Perl on Windows)
- Python 3 with PyYAML
- g++ (for building tilegen — Strawberry Perl MinGW on Windows)

### Setup

```bash
# Initialize the crawl submodule
git submodule update --init

# Run setup (from android-console-patches/)
cd android-console-patches
bash setup.sh

# Build debug APK (from project root)
cd ..
./gradlew assembleDebug
```

The debug APK will be at `build/outputs/apk/debug/dcss-ascii-android-debug.apk`.

### Rebuilding after upstream or patch changes

Re-run `setup.sh` whenever you:

- Bump the `android-crawl-console/` submodule pointer (e.g., DCSS upstream version bump)
- Modify anything under `android-console-patches/` (`Android.mk`, `Application.mk`, `libandroid.cc`, or any `*.patch`)

`setup.sh` re-applies custom files and patches into the submodule, regenerates auto-generated headers from submodule sources, and recomputes `assets/dat-hash.txt` — a content hash that `SplashActivity` uses to decide whether to re-extract game data on install. Skipping it can ship an APK whose stored hash doesn't match its bundled `dat/` content, leaving existing users running new code against their old extracted files.

```bash
cd android-console-patches
bash setup.sh
cd ..
./gradlew assembleDebug
```

## License

This project is licensed under the [GNU General Public License v2.0](LICENSE) (or later), the same license as Dungeon Crawl Stone Soup.

## Credits

- [Dungeon Crawl Stone Soup](https://github.com/crawl/crawl) — Linley Henzell, the dev team, and contributors
- [dungeon-crawl-android](https://github.com/michaelbarlow7/dungeon-crawl-android) — Michael Barlow (original Android console port)
