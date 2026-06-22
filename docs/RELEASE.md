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

Publish the APK and a SHA-256 checksum as a GitHub pre-release for alpha builds.
Do not upload debug APKs as public installer packages.
