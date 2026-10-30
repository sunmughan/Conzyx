#!/usr/bin/env python3
"""Build a Conzyx PHP 8.4 runtime bundle from Ondrej (deb.sury.org) jammy
ARM64 packages, for the Ubuntu 20.04 (focal) guest rootfs.

Why this exists, and why it is NOT a plain jammy overlay: the guest is
focal (glibc 2.31) and Ondrej dropped focal entirely (its focal index is
an empty 20-byte gzip since 2025-06-09), so no focal PHP 8.4 .debs exist
anywhere. Ondrej jammy ships PHP 8.4.27, but its binaries require
GLIBC_2.32-2.35 and would fail to start on the focal guest. This script
therefore relocates the jammy closure's shared libraries into a private
prefix and rewrites the loader linkage with patchelf:

  - The PHP 8.4 binary, its extensions, and the unzip helpers keep their
    standard guest paths (/usr/bin/php8.4, /usr/lib/php/20240924/*.so),
    so the app's existing binary checks and extension_dir keep working.
  - Every jammy shared library (libc6, ld.so, libxml2, libcurl, libssl,
    libzip, libonig, libsqlite3, libsodium, libargon2, libpcre2, libz,
    libreadline, libgcc_s, libstdc++, and the rest of the closure) lives
    in /opt/php8.4/lib inside the overlay.
  - patchelf sets the PHP binary's interpreter to the private ld.so and
    sets DT_RPATH=/opt/php8.4/lib on the binary, every extension, and
    every private library, so all dependencies resolve inside the prefix
    and never against the focal system libraries.
  - The guest's own NSS modules (libnss_files/dns) are deliberately NOT
    bundled: an older NSS module loading under a newer libc is the
    backward-compatible direction, and the guest's modules match its
    /etc/nsswitch.conf and /etc/resolv.conf.

Trust model, same as build-cpp-php-from-ubuntu-ports.py: every .deb's
SHA-256 is verified against its package index before extraction, Composer
is verified against getcomposer.org, and the script refuses to continue
on any failed check. The PHP version is gated in code: if the resolved
php8.4-cli version does not start with "8.4.", the build aborts. No other
PHP version can come out of this script by accident.

Extension parity with the old 7.4 bundle: the 7.4 bundle carried 29
extension .so files. PHP 8.4 carries 28 .so files plus json compiled
into the binary (upstream PHP 8.0+ change; Ondrej ships no php8.4-json
package), so the functional set is the same 29 extensions. The script
asserts the exact 28-stem set below.

Usage:
  python3 build-php84-from-ondrej.py --version 2026.10.2
"""

import argparse
import fnmatch
import gzip
import hashlib
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

ONDREJ = "https://ppa.launchpadcontent.net/ondrej/php/ubuntu"
PORTS = "https://ports.ubuntu.com/ubuntu-ports"
SEEDS = ["php8.4-cli", "php8.4-mbstring", "php8.4-xml", "php8.4-curl", "php8.4-zip", "unzip"]
PRIVATE_LIB = "opt/php8.4/lib"
# Extension stems in the old 7.4 bundle, minus json (built into PHP 8.4).
EXPECTED_EXTENSIONS = {
    "calendar", "ctype", "curl", "dom", "exif", "ffi", "fileinfo", "ftp",
    "gettext", "iconv", "mbstring", "opcache", "pdo", "phar", "posix",
    "readline", "shmop", "simplexml", "sockets", "sysvmsg", "sysvsem",
    "sysvshm", "tokenizer", "xml", "xmlreader", "xmlwriter", "xsl", "zip",
}
COMPOSER_URL = "https://getcomposer.org/composer-stable.phar"
# patchelf binary: from PATH, or set PATCHELF=/path/to/patchelf.
PATCHELF = os.environ.get("PATCHELF", "patchelf")


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "conzyx-bundle-builder/1.0"})
    with urllib.request.urlopen(req, timeout=120) as response:
        return response.read()


def parse_packages(blob: bytes) -> dict:
    text = gzip.decompress(blob).decode("utf-8", "replace")
    packages, current = {}, {}
    for line in text.splitlines():
        if not line.strip():
            if "Package" in current:
                packages[current["Package"]] = current
            current = {}
            continue
        if line.startswith((" ", "\t")):
            continue
        key, _, value = line.partition(":")
        current[key.strip()] = value.strip()
    if "Package" in current:
        packages[current["Package"]] = current
    return packages


def strip_dep(dep: str) -> str:
    dep = re.sub(r"\s*\([^)]*\)", "", dep.strip())
    return re.sub(r":any$", "", dep).strip()


def split_alternatives(field: str) -> list:
    result = []
    for group in field.split(","):
        group = group.strip()
        if group:
            result.append([strip_dep(a) for a in group.split("|") if strip_dep(a)])
    return result


def resolve(seeds: list, index: dict, provides: dict) -> set:
    resolved, queue, missing = set(), list(seeds), []
    while queue:
        name = queue.pop()
        if name in resolved:
            continue
        entry = index.get(name)
        if entry is None:
            providers = provides.get(name, [])
            if providers:
                entry = index.get(sorted(providers)[0])
                name = entry["Package"]
            else:
                missing.append(name)
                continue
        resolved.add(name)
        for field in ("Pre-Depends", "Depends"):
            for alternatives in split_alternatives(entry.get(field, "")):
                chosen = None
                for alt in alternatives:
                    if alt in index:
                        chosen = alt
                        break
                if chosen is None:
                    for alt in alternatives:
                        if alt in provides:
                            chosen = sorted(provides[alt])[0]
                            break
                if chosen is None:
                    missing.append(alternatives[0])
                else:
                    queue.append(chosen)
    if missing:
        raise SystemExit(f"Unresolvable dependencies: {sorted(set(missing))}")
    return resolved


def is_elf(path: Path) -> bool:
    try:
        with open(path, "rb") as fh:
            return fh.read(4) == b"\x7fELF"
    except OSError:
        return False


def copy_entry(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    if src.is_symlink():
        target = os.readlink(src)
        if "/" in target:
            # A path-relative link only resolves inside a full rootfs; in
            # the flat private prefix it would dangle, so copy the content
            # it points to instead (resolving within the source tree).
            resolved = (src.parent / target).resolve()
            if resolved.is_file():
                if dst.exists() or dst.is_symlink():
                    dst.unlink()
                shutil.copy2(resolved, dst)
                return
        if dst.exists() or dst.is_symlink():
            dst.unlink()
        dst.symlink_to(target)
    else:
        shutil.copy2(src, dst)


LIB_TOP_DIRS = {"lib/aarch64-linux-gnu", "usr/lib/aarch64-linux-gnu"}


def is_top_level_lib(rel: Path) -> bool:
    return str(rel.parent) in LIB_TOP_DIRS


def readelf(path: Path, *args: str) -> str:
    return subprocess.run(["readelf", *args, str(path)],
                          capture_output=True, text=True, check=True).stdout


def build(version: str, work: Path, out: Path) -> Path:
    index, provides, origin = {}, {}, {}
    sources = [
        ("ondrej", f"{ONDREJ}/dists/jammy/main/binary-arm64/Packages.gz"),
        ("ports-main", f"{PORTS}/dists/jammy/main/binary-arm64/Packages.gz"),
        ("ports-universe", f"{PORTS}/dists/jammy/universe/binary-arm64/Packages.gz"),
        ("ports-updates-main", f"{PORTS}/dists/jammy-updates/main/binary-arm64/Packages.gz"),
        ("ports-updates-universe", f"{PORTS}/dists/jammy-updates/universe/binary-arm64/Packages.gz"),
    ]
    for label, url in sources:
        print(f"index: {label} {url}")
        for name, entry in parse_packages(fetch(url)).items():
            if name not in index:  # first source wins: Ondrej preferred
                index[name] = entry
                origin[name] = label
    for name, entry in index.items():
        for provided in entry.get("Provides", "").split(","):
            provided = strip_dep(provided)
            if provided:
                provides.setdefault(provided, []).append(name)

    # HARD GATE: PHP must be 8.4 exactly. Anything else aborts the build.
    php_cli = index.get("php8.4-cli")
    if php_cli is None:
        raise SystemExit("php8.4-cli not found in the Ondrej jammy index")
    php_version = php_cli.get("Version", "")
    print(f"php8.4-cli version: {php_version}")
    if not php_version.startswith("8.4."):
        raise SystemExit(f"REFUSING: resolved PHP version is {php_version}, not 8.4.x")

    resolved = resolve(SEEDS, index, provides)
    print(f"resolved {len(resolved)} packages")

    debs = work / "debs"
    debs.mkdir(parents=True, exist_ok=True)
    for name in sorted(resolved):
        entry = index[name]
        filename = entry["Filename"]
        base = ONDREJ if origin[name] == "ondrej" else PORTS
        dest = debs / Path(filename).name
        if not dest.exists():
            dest.write_bytes(fetch(f"{base}/{filename}"))
        digest = hashlib.sha256(dest.read_bytes()).hexdigest()
        if digest != entry["SHA256"].lower():
            raise SystemExit(f"SHA-256 mismatch for {name}: {digest} != {entry['SHA256']}")
        if dest.stat().st_size != int(entry["Size"]):
            raise SystemExit(f"Size mismatch for {name}")

    # Extract each package separately so assembly can tell PHP files apart
    # from the shared libraries that must move into the private prefix.
    pkgdirs = {}
    for name in sorted(resolved):
        entry = index[name]
        deb = debs / Path(entry["Filename"]).name
        pkgdir = work / "pkgs" / name
        shutil.rmtree(pkgdir, ignore_errors=True)
        pkgdir.mkdir(parents=True)
        subprocess.run(["dpkg-deb", "-x", str(deb), str(pkgdir)], check=True)
        pkgdirs[name] = pkgdir

    root = work / "root"
    shutil.rmtree(root, ignore_errors=True)
    root.mkdir(parents=True)
    private = root / PRIVATE_LIB
    private.mkdir(parents=True)

    php_pkg_names = [n for n in resolved if n == "php-common" or n.startswith("php8.4")]
    for name in sorted(php_pkg_names):
        for path in sorted(pkgdirs[name].rglob("*")):
            rel = path.relative_to(pkgdirs[name])
            if path.is_dir() and not path.is_symlink():
                continue
            # Shared libraries shipped by a PHP package (none expected, but
            # be safe) belong in the private prefix, like every other .so.
            if fnmatch.fnmatch(path.name, "*.so*") and is_top_level_lib(rel):
                copy_entry(path, private / path.name)
            else:
                copy_entry(path, root / rel)
    for name in sorted(resolved):
        if name in php_pkg_names or name == "unzip":
            continue
        for path in sorted(pkgdirs[name].rglob("*")):
            if path.is_dir() and not path.is_symlink():
                continue
            rel = path.relative_to(pkgdirs[name])
            # Only top-level shared libraries move into the private prefix.
            # Binaries, docs, perl modules, gconv tables, engines, and
            # everything else the guest already provides is left out
            # (subtract before you add).
            if fnmatch.fnmatch(path.name, "*.so*") and is_top_level_lib(rel):
                copy_entry(path, private / path.name)
    # unzip helpers keep their standard paths (parity with the 7.4 bundle),
    # but they are jammy binaries, so they get the patchelf treatment too.
    for tool in ("unzip", "unzipsfx"):
        src = pkgdirs["unzip"] / "usr" / "bin" / tool
        if src.exists():
            copy_entry(src, root / "usr" / "bin" / tool)

    for sub in ("usr/share/doc", "usr/share/man"):
        shutil.rmtree(root / sub, ignore_errors=True)

    # Symlink layout: /usr/bin/php -> php8.4, exactly like the 7.4 bundle.
    php_bin = root / "usr" / "bin" / "php8.4"
    if not php_bin.exists():
        raise SystemExit("No php8.4 binary found in extracted packages")
    php_link = root / "usr" / "bin" / "php"
    if not php_link.exists() and not php_link.is_symlink():
        php_link.symlink_to("php8.4")

    # Extension directory: discover it (PHP 8.4 API dir is 20240924).
    php_lib = root / "usr" / "lib" / "php"
    api_dirs = [d for d in php_lib.iterdir() if d.is_dir() and d.name.isdigit()] if php_lib.exists() else []
    if len(api_dirs) != 1:
        raise SystemExit(f"Expected exactly one PHP API dir, found: {[d.name for d in api_dirs]}")
    ext_dir = api_dirs[0]
    stems = sorted(p.stem for p in ext_dir.glob("*.so"))
    if set(stems) != EXPECTED_EXTENSIONS:
        raise SystemExit(
            f"Extension set mismatch.\n  missing: {sorted(EXPECTED_EXTENSIONS - set(stems))}\n"
            f"  extra: {sorted(set(stems) - EXPECTED_EXTENSIONS)}"
        )
    print(f"extensions: {len(stems)} (.so) + json built into the binary = 29 functional")

    # Replicate Debian's postinst effects (mods-available + cli/conf.d),
    # or no extension would load at all on an overlay install.
    mods = root / "etc" / "php" / "8.4" / "mods-available"
    confd = root / "etc" / "php" / "8.4" / "cli" / "conf.d"
    mods.mkdir(parents=True, exist_ok=True)
    confd.mkdir(parents=True, exist_ok=True)
    for so in sorted(ext_dir.glob("*.so")):
        stem = so.stem
        ini = mods / f"{stem}.ini"
        if stem == "opcache":
            ini.write_text("; configuration for php opcache module\n; priority=10\nzend_extension=opcache.so\n")
            priority = "10"
        else:
            ini.write_text(f"; configuration for php {stem} module\n; priority=20\nextension={stem}.so\n")
            priority = "20"
        link = confd / f"{priority}-{stem}.ini"
        if not link.exists() and not link.is_symlink():
            link.symlink_to(f"../../mods-available/{ini.name}")

    # Composer, verified exactly like the focal builder.
    composer = root / "usr" / "local" / "bin" / "composer"
    composer.parent.mkdir(parents=True, exist_ok=True)
    print(f"composer: {COMPOSER_URL}")
    checksum_text = fetch("https://getcomposer.org/download/latest-stable/composer.phar.sha256sum").decode()
    expected_sha = checksum_text.split()[0].strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha):
        raise SystemExit("Could not read a valid Composer SHA-256 checksum")
    composer_bytes = fetch(COMPOSER_URL)
    actual_sha = hashlib.sha256(composer_bytes).hexdigest()
    if actual_sha != expected_sha:
        raise SystemExit(f"Composer SHA-256 mismatch: {actual_sha} != {expected_sha}")
    composer.write_bytes(composer_bytes)
    composer.chmod(0o755)

    # patchelf: private interpreter + RPATH on the binaries, RPATH on every
    # extension and every private library (so transitive deps resolve too).
    interp = f"/{PRIVATE_LIB}/ld-linux-aarch64.so.1"
    rpath = f"/{PRIVATE_LIB}"
    if not (private / "ld-linux-aarch64.so.1").exists():
        raise SystemExit("Private ld.so missing from the closure")
    patchelf_targets = [php_bin, root / "usr" / "bin" / "unzip", root / "usr" / "bin" / "unzipsfx"]
    patchelf_targets += sorted(ext_dir.glob("*.so"))
    patchelf_targets += [p for p in sorted(private.iterdir()) if is_elf(p)]
    for target in patchelf_targets:
        if not target.exists() or target.is_symlink():
            continue
        cmd = [PATCHELF, "--force-rpath", "--set-rpath", rpath, str(target)]
        if target == php_bin:
            cmd = [PATCHELF, "--set-interpreter", interp,
                   "--force-rpath", "--set-rpath", rpath, str(target)]
        subprocess.run(cmd, check=True)

    # VERIFICATION (all structural; no ARM execution exists in the sandbox).
    interp_out = readelf(php_bin, "--program-headers")
    if interp not in interp_out:
        raise SystemExit(f"Interpreter not set to {interp}")
    for target in [php_bin, *sorted(ext_dir.glob("*.so"))]:
        if target.is_symlink():
            continue
        dyn = readelf(target, "-d")
        if rpath not in dyn or "RPATH" not in dyn:
            raise SystemExit(f"RPATH {rpath} missing on {target}")
        machine = readelf(target, "-h")
        if "AArch64" not in machine:
            raise SystemExit(f"Not an AArch64 ELF: {target}")
    # Every NEEDED entry must resolve inside the private prefix.
    available = {p.name for p in private.iterdir()}
    for target in [php_bin, *sorted(ext_dir.glob("*.so"))]:
        if target.is_symlink():
            continue
        dyn = readelf(target, "-d")
        for needed in re.findall(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", dyn):
            if needed not in available and needed != "ld-linux-aarch64.so.1":
                raise SystemExit(f"Unresolved NEEDED {needed} for {target}")
    # Bundled libc must provide every GLIBC version any bundle ELF asks for.
    libc = private / "libc.so.6"
    provided = set(re.findall(r"GLIBC_(\d+\.\d+)", readelf(libc, "--version-info")))
    for target in [php_bin, *sorted(ext_dir.glob("*.so"))]:
        if target.is_symlink():
            continue
        for want in set(re.findall(r"GLIBC_(\d+\.\d+)", readelf(target, "--version-info"))):
            if want not in provided and tuple(map(int, want.split("."))) > (2, 17):
                raise SystemExit(f"GLIBC_{want} required by {target} but not in bundled libc")
    with open(composer, "rb") as fh:
        composer_head = fh.read(64)
    if not composer_head.startswith(b"#!/usr/bin/env php"):
        raise SystemExit("Composer shebang is not #!/usr/bin/env php")

    script_dir = Path(__file__).resolve().parent
    tar_path = work / "php.tar"
    subprocess.run(
        [sys.executable, str(script_dir / "create_deterministic_tar.py"), str(root), str(tar_path)],
        check=True,
    )
    out.mkdir(parents=True, exist_ok=True)
    final = out / f"conzyx-php-arm64-{version}.tar.zst"
    import zstandard

    cctx = zstandard.ZstdCompressor(level=19)
    with open(tar_path, "rb") as source, open(final, "wb") as target:
        with cctx.stream_writer(target) as compressor:
            shutil.copyfileobj(source, compressor, length=1024 * 1024)
    sha256 = hashlib.sha256(final.read_bytes()).hexdigest()
    print(f"bundle: {final}")
    print(f"size: {final.stat().st_size} bytes compressed, {tar_path.stat().st_size} bytes tar")
    print(f"sha256: {sha256}")
    print(f"php version: {php_version}")
    return final


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--work", default="bundle-work-php84")
    parser.add_argument("--out", default="bundle-out-php84")
    args = parser.parse_args()
    build(args.version, Path(args.work), Path(args.out))


if __name__ == "__main__":
    main()
