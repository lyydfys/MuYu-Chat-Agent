# Release Guide

MCA release APKs should be published through GitHub Releases, not committed to
the source tree.

## Signing

Create a local `signing.properties` file from `signing.properties.example`.
The file and keystore are ignored by Git.

Required keys:

```properties
storeFile=.signing/mca-release.jks
storePassword=...
keyAlias=mca-release
keyPassword=...
```

## Build an arm64 release APK

```powershell
$env:JAVA_HOME='<path-to-jdk-17>'
$env:ANDROID_HOME='<path-to-android-sdk>'
.\gradlew.bat :app:assembleRelease -Pmca.abis=arm64-v8a
```

The APK is generated under:

```text
app/build/outputs/apk/release/
```

## Publish

Do not upload debug APKs as public installer packages. Publish the signed
arm64 release APK and its SHA-256 checksum as GitHub Release assets.

For a stable release, first set a non-prerelease `versionName`, increment
`versionCode`, run the full JVM test matrix, build the signed arm64 release
with the QAIRT SDK configured, verify its APK v2 signature, and create a tag
named `v<version>` at the verified source commit. The release notes must state
the package version, ABI, checksum, validation commands, and experimental
capabilities.

For an alpha release, keep the prerelease suffix in `versionName` and create
the GitHub Release with its prerelease flag enabled.

## In-app update contract

The app checks the official `lyydfys/MuYu-Chat-Agent` GitHub Release from the
Runtime settings screen (automatically at most once per 24 hours, or manually).
This is client-side polling; it does not require FCM or a separate update
server. Only a non-draft, non-prerelease Release with a valid stable SemVer
tag is offered.

For the updater to recognize an asset, publish the signed APK and a checksum
alongside the Release:

```text
MCA-v<version>-arm64-v8a.apk
MCA-v<version>-arm64-v8a.apk.sha256
```

The current build also supports `x86_64` and a single ABI-neutral APK whose
filename has no ABI marker. ABI-specific assets must use the corresponding
marker; the updater never silently installs another architecture. The APK
must keep the same package name and release signing certificate, use a
`versionName` matching the tag (for example, tag `v0.3.0` and version name
`0.3.0`), and increment `versionCode`.

Before opening the Android installer, MCA validates the GitHub HTTPS asset,
SHA-256 (API digest or the `.sha256` sidecar), package name, version name, and
version code. Keep the Release body focused on user-facing update notes; it is
shown in the in-app update card.
