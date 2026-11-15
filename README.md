# maclook4sat

**Look4Sat for Intel and Apple Silicon macOS — an unofficial, experimental desktop port of the Android satellite tracker.**

Based on [Look4Sat 4.4.9 by rt-bishop](https://github.com/rt-bishop/Look4Sat). The original project is free and open source under GPL-3.0. This macOS adaptation is maintained independently by [z1000biker](https://github.com/z1000biker).

![Look4Sat-Mac proof of concept running on Intel macOS — radar, satellite list and pass predictions](Look4Sat-Mac-PoC.png)

*Look4Sat-Mac PoC running on Intel macOS (screenshot from 15 November 2026). The screenshot demonstrates the application UI, not full functional or stability validation.*

## Automated builds: Intel and Apple Silicon

[GitHub Actions build #1](https://github.com/z1000biker/maclook4sat/actions/runs/43763430428) completed successfully on **15 November 2026** using Java 21 and Gradle 9.8.1. The workflow compiled and packaged two architecture-specific macOS application bundles:

| Runner | CPU architecture | CI result | Download |
| --- | --- | --- | --- |
| `macos-15-intel` | Intel `x86_64` | Build and offline checks passed | [`Look4Sat-Mac-4.4.9-Intel-CI-UNTESTED.zip`](https://github.com/z1000biker/maclook4sat/releases/download/v4.4.9-macos-poc/Look4Sat-Mac-4.4.9-Intel-CI-UNTESTED.zip) in the Release |
| `macos-15` | Apple Silicon `arm64` | Build and offline checks passed | [`Look4Sat-Mac-4.4.9-AppleSilicon-CI-UNTESTED.zip`](https://github.com/z1000biker/maclook4sat/releases/download/v4.4.9-macos-poc/Look4Sat-Mac-4.4.9-AppleSilicon-CI-UNTESTED.zip) in the Release |

**The Apple Silicon version has NOT been tested on an actual Apple Silicon Mac.** Successful compilation, offline regression checks, ad-hoc code signing and packaging do not establish correct runtime behavior, device compatibility or satellite-tracking accuracy. The new CI-generated Intel artifact has also not undergone independent hands-on validation. Both artifacts are experimental.

**Both experimental CI builds are attached to the [v4.4.9 macOS PoC Release](https://github.com/z1000biker/maclook4sat/releases/tag/v4.4.9-macos-poc)** alongside the earlier Intel ZIP and full source archive. The artifacts on the Actions run page expire after 30 days; the copies in the Release are not tied to artifact retention. Rebuild through **Actions → Build macOS (Intel and Apple Silicon) → Run workflow**. Publishing a new build to a Release is a separate operation via **Actions → Publish experimental macOS builds to Release → Run workflow**.

> [!WARNING]
> **Development proof of concept — not production-ready.** The app has launched on an Intel Mac, but complete feature parity and stability have **not** been established. A system restart was reported while testing an earlier satellite switch. Use at your own risk and save other work before testing.

## Download

See the [Releases page](https://github.com/z1000biker/maclook4sat/releases) for the current pre-release and its files:

| File | Contents |
| --- | --- |
| `Look4Sat-Mac-4.4.9-Intel.zip` | Original Intel macOS application bundle with bundled Java runtime (earlier PoC) |
| `Look4Sat-Mac-4.4.9-Intel-CI-UNTESTED.zip` | Fresh GitHub Actions Intel `x86_64` build; **not independently tested on a real Mac** |
| `Look4Sat-Mac-4.4.9-AppleSilicon-CI-UNTESTED.zip` | GitHub Actions Apple Silicon `arm64` build; **not tested on a real Apple Silicon Mac** |
| `Look4Sat-Mac-4.4.9-source.zip` | Full corresponding source archive: original Look4Sat modules plus `desktop-macos/` |
| `Look4Sat-Mac.zip` (if available) | Alternative ZIP of the same application bundle, exported from macOS Finder |

**There is no need to install Java separately to launch the packaged application.** The ZIP contains `Look4Sat-Mac.app/Contents/runtime/`.

## Install on macOS

1. Download a ZIP for **your CPU architecture** from the [v4.4.9 Release](https://github.com/z1000biker/maclook4sat/releases/tag/v4.4.9-macos-poc): `Look4Sat-Mac-4.4.9-AppleSilicon-CI-UNTESTED.zip` for M-series Macs; `Look4Sat-Mac-4.4.9-Intel-CI-UNTESTED.zip` or the earlier `Look4Sat-Mac-4.4.9-Intel.zip` for Intel Macs.
2. Extract the ZIP and move `Look4Sat-Mac.app` to `/Applications`, if desired.
3. Open `Look4Sat-Mac.app` from Finder. On systems that block unnotarized apps, inspect the app's origin first and use the normal macOS **Privacy & Security → Open Anyway** control only if you trust this experimental build.
4. Enter your station latitude, longitude and altitude. Download orbital data or import TLE/OMM files, then select a satellite.

The application is **not Apple-notarized**. Both Intel (`x86_64`) and Apple Silicon (`arm64`) ZIP archives are available in the Release. **Neither new CI-generated archive has been independently tested on real Mac hardware; Apple Silicon runtime support has not been verified.** Hardware integration and permissions depend on your macOS configuration.

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

Clone this repository or download and extract `Look4Sat-Mac-4.4.9-source.zip`. On a Mac with **JDK 21** installed, from the repository directory or extracted source directory:

```bash
cd desktop-macos
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
