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
