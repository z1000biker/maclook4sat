# maclook4sat

**Look4Sat for Intel macOS — an unofficial, experimental desktop port of the Android satellite tracker.**

Based on [Look4Sat 4.4.9 by rt-bishop](https://github.com/rt-bishop/Look4Sat). The original project is free and open source under GPL-3.0. This macOS adaptation is maintained independently by [z1000biker](https://github.com/z1000biker).

![Look4Sat-Mac proof of concept running on Intel macOS — radar, satellite list and pass predictions](Look4Sat-Mac-PoC.png)

*Look4Sat-Mac PoC running on Intel macOS (screenshot from 15 November 2026). The screenshot demonstrates the application UI, not full functional or stability validation.*

> [!WARNING]
> **Development proof of concept — not production-ready.** The app has launched on an Intel Mac, but complete feature parity and stability have **not** been established. A system restart was reported while testing an earlier satellite switch. Use at your own risk and save other work before testing.

## Download

See the [Releases page](https://github.com/z1000biker/maclook4sat/releases) for the current pre-release and its files:

| File | Contents |
| --- | --- |
| `Look4Sat-Mac-4.4.9-Intel.zip` | Intel macOS application bundle with bundled Java runtime |
| `Look4Sat-Mac-4.4.9-source.zip` | Full corresponding source archive: original Look4Sat modules plus `desktop-macos/` |
| `Look4Sat-Mac.zip` (if available) | Alternative ZIP of the same application bundle, exported from macOS Finder |

**There is no need to install Java separately to launch the packaged application.** The ZIP contains `Look4Sat-Mac.app/Contents/runtime/`.

## Install on macOS

1. Download `Look4Sat-Mac-4.4.9-Intel.zip` from [Releases](https://github.com/z1000biker/maclook4sat/releases).
2. Extract the ZIP and move `Look4Sat-Mac.app` to `/Applications`, if desired.
3. Open `Look4Sat-Mac.app` from Finder. On systems that block unnotarized apps, inspect the app's origin first and use the normal macOS **Privacy & Security → Open Anyway** control only if you trust this experimental build.
4. Enter your station latitude, longitude and altitude. Download orbital data or import TLE/OMM files, then select a satellite.

The application is **not Apple-notarized**. Hardware integration and permissions depend on your macOS configuration. The available package is for Intel (`x86_64`) macOS; Apple Silicon support has not been verified.

## What is implemented?

The desktop PoC presents the following areas. A visible tab does **not** imply that the feature is fully functional:

| Area | Current status |
| --- | --- |
| Radar / satellite position | UI demonstrated on Intel macOS |
| Satellite list and NORAD selection | UI demonstrated; repeat satellite-switch stability not established |
| Pass predictions / upcoming passes | UI demonstrated; further accuracy testing needed |
| Map and ground track | Implemented as experimental desktop UI |
| Status and station settings | Implemented as experimental desktop UI |
| SSTV | Experimental audio/decoder integration; device testing incomplete |
| Transceivers / CAT control | Experimental; real-radio testing incomplete |
| Remote control | Experimental; not independently validated |
| SatNOGS / AMSAT status | Experimental; network data and feature coverage need verification |

The orbit calculation code is derived from the original Look4Sat engine. The macOS user interface is a separate Kotlin/JVM desktop implementation, rather than the original Android interface running natively.

## Build from source

Download and extract `Look4Sat-Mac-4.4.9-source.zip`. On a Mac with **JDK 21** installed, from the extracted root:

```bash
cd Look4Sat-4.4.9/desktop-macos
./gradlew verifyDesktop
./gradlew run
./gradlew packageMac
```

The packaged application is expected under `desktop-macos/build/macos/Look4Sat-Mac.app`. `packageMac` invokes the JDK's `jpackage` and performs ad-hoc macOS signing. It is not equivalent to an Apple Developer ID signature or notarization.

The desktop Gradle project uses Kotlin/JVM 2.4.21 and JDK 21. It reuses portions of `core/domain` and CAT protocol sources from the original repository, so **retain the complete extracted source tree** when building.

## Limitations and reporting

- Treat unexpected crashes, incorrect pass data, satellite-switching issues and USB/serial device problems as possible.
- The ZIP archives passed integrity checks, but passing a ZIP integrity check does **not** certify application stability.
- When reporting a problem, include macOS version, Intel Mac model, selected satellite/NORAD ID, action sequence and relevant app logs—without passwords or tokens.
- Report issues in [GitHub Issues](https://github.com/z1000biker/maclook4sat/issues).

## Credits and license

Original project: [rt-bishop/Look4Sat](https://github.com/rt-bishop/Look4Sat), licensed under **GNU General Public License v3.0**. This repository is an **unofficial independent port**, not an endorsed upstream release. The source archive preserves original notices and the GPL license; see [LICENSE](LICENSE).
