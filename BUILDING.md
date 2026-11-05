# Build Pause

This repository includes the current Android source, resources, tests, Gradle wrapper and configuration. It is a clean snapshot, without private development history or release signing credentials.

## Requirements

- JDK 17+ (releases use JBR 21).
- Android SDK platform 36 and the build tools requested by Gradle.
- Internet for dependencies on the first build.

Set `ANDROID_HOME` to your SDK or create an untracked `local.properties` with `sdk.dir=...`.

macOS/Linux:

```sh
chmod +x gradlew
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is in `app/build/outputs/apk/debug/`. Its debug signature **cannot update the official installation**. Use a test device or emulator; uninstalling your everyday copy loses its local data.

For your own signed release, create an untracked `signing.properties`:

```properties
storeFile=/private/path/to/your-key.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=YOUR_ALIAS
keyPassword=YOUR_PRIVATE_PASSWORD
```

Run `:app:assembleRelease`. Your signature is your own, not the official one. Never commit keys, passwords, local device data or signing settings.

## Tests and limits

Unit tests cover DNS packets, IPv4/IPv6, TCP sessions, rule precedence, list validation, memory budgets, update scheduling and rule reloads. Passing doesn't establish universal ad blocking or full UI/device compatibility.

`probe` and the instrumentation runner are development tools. `ProtectionRunner` is restricted to disposable emulators. Read runners first: they change test app data and VPN state. Always specify an adb serial and never use these runners on a personal phone.

## Lists and licenses

Snapshots and attributions are in `app/src/main/assets/`. Original HaGeZi data is also supplied separately in `filters/`. Runtime refresh is implemented; routine list changes don't require a new APK.

Original Pause code is GPL-3.0-only. See [LICENSE](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md). No byte-for-byte reproducible-build claim is made for the official signed APK.
