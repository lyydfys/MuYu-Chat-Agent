# MCA SPDX SBOM

<code>generate_sbom.py</code> builds a deterministic SPDX 2.3 JSON inventory from the current
repository and locally available SDK manifests. It performs static parsing only and never calls
Gradle or the network.

## Generate and verify

From the repository root:

~~~powershell
$env:PYTHONDONTWRITEBYTECODE='1'
python -B tools/sbom/generate_sbom.py
python -B tools/sbom/generate_sbom.py --verify
~~~

The compact POSIX form is:

~~~text
PYTHONDONTWRITEBYTECODE=1 python -B tools/sbom/generate_sbom.py --verify
~~~

On cmd.exe, use:

~~~text
set PYTHONDONTWRITEBYTECODE=1&& python -B tools\sbom\generate_sbom.py --verify
~~~

To bind a final APK to the inventory:

~~~powershell
python -B tools/sbom/generate_sbom.py --apk app/build/outputs/apk/release/app-release.apk
python -B tools/sbom/generate_sbom.py --verify --apk app/build/outputs/apk/release/app-release.apk
~~~

An APK package is emitted only when <code>--apk</code> is supplied. Without it, the SBOM does not
contain a placeholder or forged APK package. A bound APK records:

- package name <code>mca-android-apk</code>, version, supplier/originator, and <code>filesAnalyzed=false</code>;
- SHA-256 and byte size, with the hash checked again by <code>--verify</code>;
- ABIs found under <code>lib/&lt;abi&gt;/</code> in the ZIP and a deterministic <code>variation</code> comment;
- document <code>DESCRIBES</code>, <code>CONTAINS</code>, and <code>CONTAINED_BY</code> relationships.

The APK hash proves byte identity of the artifact under review. It does not prove that the APK was
built from the current dirty working tree.

## Repository inputs

The generator reads, in sorted order:

1. <code>gradle/libs.versions.toml</code> and other non-generated <code>*.versions.toml</code> catalogs;
2. <code>settings.gradle.kts</code>, then the reachable <code>:app</code> project graph;
3. production dependency declarations in each reachable <code>build.gradle.kts</code> (<code>implementation</code>,
   <code>api</code>, <code>compileOnly</code>, <code>runtimeOnly</code>, and code-generation/build configurations);
4. <code>vendor/mnn/mnn-vendor.properties</code>, <code>vendor/llama/llama-vendor.properties</code>,
   <code>vendor/tokenizers-cpp.properties</code>, <code>vendor/geniex/</code> vendor manifests and AARs,
   and Qwen runtime files;
5. <code>.gitmodules</code> plus local Git submodule commits for llama.cpp, stable-diffusion.cpp, and
   tokenizers-cpp;
6. local QAIRT <code>sdk.yaml</code> (environment candidates plus repository/local-tool fallbacks) and Android
   SDK/NDK <code>source.properties</code>;
7. Maven BOM metadata already present in the local Gradle cache, when available;
8. the optional APK passed with <code>--apk</code>.

Only <code>build.gradle.kts</code> and <code>src/main</code> are included for MCA module source packages. Vendored
binaries, properties, patches, notices, selected upstream source files, and their hashes are
included where they exist locally.

## Provenance markers

Every package has a <code>comment</code> beginning with a provenance marker:

- <code>provenance=repository-input</code>: the identity and/or content was read from current repository,
  Git, SDK, or cache inputs;
- <code>provenance=qairt-sdk-manifest</code>: the version comes from the locally available QAIRT SDK manifest;
- <code>provenance=static-fallback</code>: a compatibility or unresolved label was retained because no
  authoritative local version was available.

Static fallbacks are intentionally visible in both JSON and this README. The current fallback
classes are:

- legacy module labels <code>mca-core-engine</code> and <code>mca-native-common</code> at <code>1.0.0-sgrade</code>; the JSON
  <code>sourceInfo</code> carries the current app version;
- llama.cpp and stable-diffusion.cpp compatibility labels retained for release-tool compatibility;
  <code>sourceInfo</code> and <code>externalRefs</code> carry the resolved Git commit and repository URL;
- Qwen-Image-2.1's <code>2.1-mca-pinned</code> label because its runtime manifest records file hashes but no
  component version.

Unresolved BOM-managed Maven versions use <code>NOASSERTION</code> and <code>provenance=static-fallback</code>. If the
Compose BOM POM is present in the local Gradle cache, its exact managed versions are used instead.

## SPDX content and verification

The output uses sorted packages, files, and relationships; stable path/name-derived SPDXIDs with
collision hashing; Git commit time (or <code>SOURCE_DATE_EPOCH</code>) for <code>creationInfo.created</code>; a
content-derived document namespace; and UTF-8 with LF line endings.

For analyzed packages the generator emits SHA-256/SHA-1 file checksums, <code>packageVerificationCode</code>,
and package <code>CONTAINS</code> file edges. Local source/artifact bundles also receive a deterministic
SHA-256 over the canonical sorted file-digest set. Direct Maven packages use a SHA-256 artifact
checksum when the artifact is present in the local Gradle cache; otherwise they bind the cached
Gradle <code>.module</code>/<code>.pom</code> metadata and expose <code>checksumScope=gradle-metadata</code>. QAIRT manifest-only
dependencies bind the local <code>sdk.yaml</code> with <code>checksumScope=qairt-sdk-manifest</code>.
The generated document uses stable logical names for local SDK/cache inputs and does not serialize
machine-specific absolute paths. <code>--verify</code> rejects local absolute paths anywhere in the SPDX document.

<code>--verify</code> checks:

- SPDX 2.3 document essentials and LF/UTF-8 output;
- duplicate package/file SPDXIDs;
- dangling or duplicate relationships and valid relationship types;
- required package version, supplier, originator, download location, and license fields;
- provenance markers and source/external-reference presence;
- SHA-1, SHA-256, and MD5 checksum syntax;
- <code>packageVerificationCode</code> recomputation from <code>hasFiles</code>;
- the presence of both <code>CONTAINS</code> and dependency edges;
- the release-critical package names and versions retained by MCA tooling;
- APK SHA-256, byte-size, and ABI/variation binding when an APK package is present.

## Known limits

- No Gradle resolution is performed. Transitive Maven dependencies and Gradle variant conflict
  resolution are not enumerated; only direct declarations in reachable production modules are
  inventoried.
- Maven license/supplier values beyond the local mapping are <code>NOASSERTION</code>; no network lookup is
  performed.
- <code>checksumScope</code> distinguishes artifact hashes from Gradle metadata or QAIRT manifest evidence;
  a metadata checksum does not assert that the binary artifact itself was hashed.
- QAIRT is outside the repository. Its local <code>sdk.yaml</code> and notices are hashed as external files,
  but the entire SDK tree is not copied or hashed.
- Submodule packages hash only the selected local evidence/source files listed in the SBOM, not
  every file in the upstream checkout.
- SBOM generation and structural verification do not constitute an S-grade, production-build, or
  device-acceptance claim.
