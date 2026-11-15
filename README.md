# maclook4sat

Unofficial **macOS development port** of [Look4Sat](https://github.com/rt-bishop/Look4Sat), based on the upstream 4.4.9 source. Maintained by [z1000biker](https://github.com/z1000biker).

> **Status: proof of concept (PoC), not a stable or feature-complete release.** The application has been launched on an Intel Mac, but the macOS implementation has not been independently validated against all Android features. A system restart was reported during earlier satellite selection testing. Use cautiously.

## Project goals

- Preserve the original Look4Sat capabilities while adapting the application to macOS.
- Support Intel macOS with a downloadable application bundle.
- Include full source code and retain all upstream license notices.
- Validate satellite switching, pass calculations, map/radar updates, data import, serial CAT control and other hardware-dependent features before declaring a stable release.

## Planned downloads

The initial development release will provide:

- `Look4Sat-Mac-4.4.9-Intel.zip` — packaged Intel macOS application.
- `Look4Sat-Mac-4.4.9-source.zip` — corresponding source archive.

Until these exact archives have been uploaded and verified, **there is no downloadable macOS release in this repository**.

## Upstream and license

Original Look4Sat by [rt-bishop](https://github.com/rt-bishop/Look4Sat), distributed under the GNU General Public License v3.0. This repository is an independent port, not an official upstream release. See [LICENSE](LICENSE).
