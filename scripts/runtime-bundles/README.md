# PocketDev runtime bundles

Runtime bundles are release artifacts, not device backups. They are built from
the official Ubuntu ARM64 base and architecture-matched upstream tool archives,
so they are portable across Android phones that expose the `arm64-v8a` ABI.

The rooted phone is only an ARM64 build host. No Motorola system files, Android
partitions, Magisk files, device identifiers, or user data are included.

## Bundle layout

- `core`: Ubuntu, Node, npm, Git, and shared runtime support. It contains no
  coding agent.
- `python`: Python, pip, venv, and build tools. Downloaded only when selected.
- `android`: a portable JDK 17, Android SDK, ARM64 build tools, Gradle, the
  offline Maven repository, and PocketDev's global ARM64 AAPT2 configuration.
- `cpp`: GCC, G++, make, CMake, and GDB.
- `php`: PHP CLI, common extensions, and Composer.
- `claude`: Anthropic's checksum-verified Claude Code ARM64 binary. Build it
  with `scripts/runtime-bundles/build-claude-from-installed-android.sh`; the
  export excludes authentication, settings, conversations, projects, and all
  device data. Online builds download it only when selected, while offline
  builds embed the same verified overlay.
- `dsh`: the pinned official DeepSeek Harness npm payload and launcher. Build it
  from a verified PocketDev installation with
  `scripts/runtime-bundles/build-dsh-from-installed-android.sh`; the export
  intentionally excludes provider settings, credentials, sessions, and projects.
  Online builds download this artifact from the runtime GitHub release; offline
  builds embed the identical checksum-verified artifact in the APK.
- `agy`: Google's checksum-pinned official Linux ARM64 Antigravity CLI binary.
  Build it with `scripts/runtime-bundles/build-agy-from-official.sh`. The overlay
  excludes OAuth credentials, settings, conversations, projects, and all device
  data. Review Google's redistribution terms before publishing an APK containing
  this binary.

The current artifact metadata and SHA-256 checksums live in
`dist/runtime-bundles/manifest.json`. Large `.tar.zst` files and downloaded
source archives are intentionally ignored by Git and must be published as
release assets or copied into the app's release asset input.

## Compatibility

These artifacts are universal for 64-bit ARM Android devices; they are not tied
to the phone used to build them. They do not support x86/x86_64 emulators or
32-bit-only ARM devices. PocketDev must continue checking for `arm64-v8a` before
installation.

## Building the C/C++ and PHP bundles

`build-cpp-php-from-ubuntu-ports.py` builds the `cpp` overlay from
Ubuntu 20.04 (focal) ARM64 packages, without needing an ARM64 host:
`.deb` data archives are architecture-independent to extract, so an x86_64
host can assemble an ARM64 overlay with `dpkg-deb`, as long as every
package comes from the focal arm64 package index and its SHA-256 is
verified against that index.

    python3 build-cpp-php-from-ubuntu-ports.py --component cpp --version 2026.10.1

The script resolves the full Depends + Pre-Depends closure for the same
seed list the installer used to apt-install (build-essential, cmake, gdb,
gcc, g++, make), verifies each `.deb` SHA-256, extracts it, then
replicates the postinst effects an overlay install would otherwise miss:
compiler alternatives symlinks (gcc/g++/cc/c++). The overlay is packed
with `create_deterministic_tar.py` and compressed with zstd level 19.

## Building the PHP 8.4 bundle

PHP is built by `build-php84-from-ondrej.py`, NOT by the focal script:
Ondrej dropped focal entirely (its focal package index is an empty
20-byte gzip since 2025-06-09), so no focal PHP 8.4 `.deb` exists. The
guest rootfs is focal (glibc 2.31), while Ondrej jammy PHP 8.4 binaries
require GLIBC_2.32-2.35 and would fail to start there, so the script
relocates the jammy closure's shared libraries into a private prefix
(`/opt/php8.4/lib` inside the overlay) and rewrites the loader linkage
with patchelf (private interpreter + `RPATH=/opt/php8.4/lib` on the PHP
binary, every extension, and every private library). The PHP binary,
extensions, and conf.d symlinks keep their standard guest paths, so the
app's binary checks and extension_dir keep working. The script refuses
to build unless the resolved php8.4-cli version starts with `8.4.`, and
it verifies every `.deb` SHA-256 against its index, every `NEEDED`
entry against the private prefix, and the extension set (28 `.so` files
plus json compiled into the PHP 8.4 binary, the same 29 functional
extensions the 7.4 bundle carried). Composer is downloaded from
getcomposer.org and verified against its published SHA-256 checksum.
patchelf must be on PATH (or set the `PATCHELF` environment variable).

    python3 build-php84-from-ondrej.py --version 2026.10.2

Publish the resulting `conzyx-php-arm64-<version>.tar.zst` file as an
asset of a `runtime-*` release on sunmughan/Conzyx, pin its SHA-256
digest in `.github/runtime-bundles.lock.json`, and CI fetches it into
the offline APK exactly like the upstream bundles.

## Release requirements

1. Build on a clean ARM64 Linux environment or a rooted ARM64 Android phone
   using a clean Ubuntu chroot.
2. Verify every downloaded source archive against its pinned SHA-256 checksum.
3. Remove package caches, logs, temporary files, resolver state, SSH host keys,
   machine IDs, and shell histories.
4. Create deterministic archives with numeric root ownership and a fixed mtime.
5. Extract each completed archive into a fresh directory and execute its tools.
6. Publish the archive and manifest together. Sign the manifest with a private
   release key kept outside this repository before production distribution.
