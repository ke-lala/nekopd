# Neko-i2pd for Android

[简体中文](README.md) · [English](README.en.md) · [Русский](README.ru.md)

An unofficial Android fork based on [PurpleI2P/i2pd-android](https://github.com/PurpleI2P/i2pd-android) 3.0.0. The app version is **3.0.1**. Branch `android-vpn-webapps` adds address-book search/import, an in-app I2P browser and per-app VPN routing while retaining i2pd's existing console and tunnel features. This is not an official PurpleI2P release.

## Screenshots

User-provided screenshots of the address book, tunnel settings, VPN routing and router status. Select an image to view it at full size.

<a href="docs/screenshots/address-book-b32.jpg"><img src="docs/screenshots/address-book-b32.jpg" width="240" alt="Address book search and b32 address"></a>

**Local address book:** search an `.i2p` hostname and view or copy its b32 address.

<a href="docs/screenshots/vpn-app-selection.jpg"><img src="docs/screenshots/vpn-app-selection.jpg" width="240" alt="VPN mode and per-app routing"></a>

**Per-app VPN:** choose which apps are routed through i2pd.

<a href="docs/screenshots/router-status.jpg"><img src="docs/screenshots/router-status.jpg" width="240" alt="i2pd router status"></a>

**Router status:** router state, network and tunnel statistics.

<a href="docs/screenshots/tunnel-settings.jpg"><img src="docs/screenshots/tunnel-settings.jpg" width="240" alt="i2pd tunnel settings"></a>

**Tunnel settings:** view and manage existing tunnel configuration.

<a href="docs/screenshots/i2p-site-in-browser.jpg"><img src="docs/screenshots/i2p-site-in-browser.jpg" width="240" alt="An I2P site opened in a browser"></a>

**I2P site:** a user-provided screenshot of an `.i2p` page.

## Features

- **Address book:** includes a 71-entry snapshot of Java I2P's release [`hosts.txt`](https://github.com/i2p/i2p.i2p/blob/b21f0c5ee22edc4fb7fa0bc4bd8cca255e629ec0/installer/resources/hosts.txt). Search hostnames, view/copy their b32 addresses, or import standard `hosts.txt` and `hostname,b32` CSV files. The bundled list is a fixed snapshot and is not automatically refreshed. Imports are additive; existing names win on conflict. Restart a running router before resolving newly imported hostnames.
- **In-app I2P browser:** uses the local i2pd HTTP proxy at `127.0.0.1:4444` and allows only `.i2p` pages and their site resources. If the system WebView does not support `PROXY_OVERRIDE`, the browser shows an error and does not fall back to a direct public-Internet connection.
- **Per-app VPN:** select apps in VPN settings. Hev Socks5 Tunnel 2.19.3 (native build targeting API 23) forwards selected-app traffic to the local i2pd SOCKS5 proxy at `127.0.0.1:4447`. Android normally allows only one active VPN. Without a working I2P outproxy, ordinary public-Internet connections may be blocked; a VPN failure does not silently fall back to direct Internet access.
- **WebApps portal:** open I2P Mail (`mail.i2p`) and Web IRC (`webirc.coldhell.i2p`) in the in-app browser. These are entry points to services on the I2P network, not mail or IRC servers bundled in the APK.
- **Console background:** choose or clear a background image from the phone's photos.
- **Existing i2pd features:** the original console and tunnel functions remain. This fork does not add a standalone BitTorrent download/seeding WebApp.

## Android compatibility

Targets common Android device and emulator combinations: **Android 6.0/API 23 or later**, with `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64` native libraries. This does not guarantee compatibility with every vendor firmware or device. The in-app browser also depends on the installed WebView supporting `PROXY_OVERRIDE`. Verification includes APK builds and unit tests; it is not a full physical-device test matrix.

## Download

Get the APK from [GitHub Releases](https://github.com/ke-lala/nekopd/releases). Version 3.0.1 is a **debug-signed test build** for sideloading. It is not signed for Google Play distribution and should not be treated as a fully audited stable release.

## Build from source

Requirements: JDK 17 or newer, Android SDK (API 37), NDK `29.0.14206865`, `make`, `cmake`, `perl`, `curl` and `zip`. Clone with the i2pd submodule:

```bash
git clone --recurse-submodules --branch android-vpn-webapps \
  https://github.com/ke-lala/nekopd.git
cd nekopd
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
binary/jni/build.sh
./gradlew :app:assembleDebug
```

The universal debug APK is written to `app/build/outputs/apk/debug/`. For a production release, sign with your own release key; do not commit keys, passwords or `keystore.properties`.

## AI assistance

AI tools assisted with implementation and with drafting and translating the README. AI assistance is not a security audit and does not mean the app has been tested on every Android device. Compatibility claims are limited to the API/ABI targets and the builds/tests that were actually run.

## Upstream projects and licenses

Based on [PurpleI2P/i2pd-android](https://github.com/PurpleI2P/i2pd-android) and [PurpleI2P/i2pd](https://github.com/PurpleI2P/i2pd). The VPN data plane uses [Hev Socks5 Tunnel](https://github.com/heiher/hev-socks5-tunnel); its MIT license is included in the project. See the upstream projects and dependency license files for details.
