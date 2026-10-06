<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Windows resident ADB activation

This launcher uses USB ADB once to activate the Android app's resident ADB service. The service runs on the phone; after Android reports `READY`, the USB cable and this PC are no longer required for that service. This flow does not use Shizuku, wireless debugging, LAN, or a desktop listener.

## Requirements

- Windows 10 or 11 and Java 17 or newer. Install Java from [Oracle's official Java downloads](https://www.oracle.com/java/technologies/downloads/) or another trusted JDK vendor.
- Google's official [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools), including `adb.exe`.
- An unlocked Android phone, USB debugging enabled, and a USB data cable.

The distribution does not include Java or Platform-Tools and does not download or install dependencies. Extract the complete ZIP before running it.

## Activate the phone

1. In mobileAgentRuntime on Android, open the resident ADB activation screen and generate a fresh activation token. The token expires on the phone; enter it promptly.
2. Connect the phone by USB. Unlock it and approve the Android USB debugging RSA prompt if shown.
3. Double-click `start-wired-adb.bat`. Enter the absolute path to the official `adb.exe` when prompted.
4. Review the device list and type the exact serial for the USB phone. No device is selected automatically; wireless/network serials are rejected. If the phone is `unauthorized`, approve its prompt and restart activation.
5. Enter the 64-hex activation token at the hidden prompt. The launcher sends a bounded binary bootstrap on ADB stdin; it does not put the token or its companion secret in command arguments, environment variables, files, or output. The Android app owns expiry and replay checks.
6. Wait for the PC message beginning `等待手机完成配对`. Return to Android and tap **Complete**, then return to the PC window and press Enter when prompted.
7. Keep USB connected until the PC reports that Android's provider is `READY`. After that, USB can be unplugged and this window can be closed; the service remains on the phone.

If activation times out or has an uncertain outcome, do not reuse the token. Start a fresh activation on Android, reconnect USB, and repeat the steps. Check the phone's USB debugging authorization and the selected device serial before retrying.

## Development-only legacy protocol

The `quickstart`, `pair`, and `run` CLI commands remain for legacy desktop-protocol development and tests. They do not activate the current Android resident service and are not a product fallback path. The packaged `start-wired-adb.bat` always uses `activate`.

## Bundled runtime libraries and licences

The ZIP bundles seven third-party runtime JARs in `lib/` alongside the two first-party bridge JARs:

- `net.java.dev.jna:jna:5.19.1` and `net.java.dev.jna:jna-platform:5.19.1`: upstream dual-licensed under Apache-2.0 or LGPL-2.1-or-later. This distribution uses the Apache-2.0 option, includes its full terms, and preserves JNA's original dual-license notice at `licenses/runtime-jars/jna-5.19.1/LICENSE`.
- `org.jetbrains:annotations:23.0.0`, `org.jetbrains.kotlin:kotlin-stdlib:2.1.10`, `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1`, `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3`, and `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3`: Apache-2.0.

The ZIP contains the first-party `LICENSE`, third-party notices, the full Apache-2.0 text at `licenses/Apache-2.0.txt`, and runtime JAR legal texts under `licenses/runtime-jars/`. Third-party components retain their upstream licences; the first-party code remains AGPL-3.0-only.
