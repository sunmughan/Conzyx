#!/usr/bin/env python3
"""Build a Conzyx CPP or PHP runtime bundle from Ubuntu 20.04 (focal) ARM64
packages, without needing an ARM64 host.

Why this exists: the offline APK cannot run apt inside PRoot on the phone
(the phone's dpkg path stalls), so C/C++ and PHP must ship as prebuilt
overlays exactly like the Python and Android bundles. A .deb data archive is
architecture-independent to extract, so an x86_64 host can assemble an ARM64
overlay with dpkg-deb, as long as every package comes from the focal arm64
package index and its SHA-256 is verified.

Method, mirroring the installer's apt lists (apt --no-install-recommends,
so Recommends are ignored):
  - Download focal + focal-updates Packages.gz (main + universe, arm64)
    from ports.ubuntu.com.
  - Resolve the full Depends + Pre-Depends closure for the seed packages
    (alternatives: first alternative present in the index; virtual packages
    resolved through Provides).
  - Download each .deb, verify its SHA-256 against the index, extract it
    with dpkg-deb -x into a staging root.
  - Replicate the postinst effects the overlay would otherwise miss:
    compiler alternatives symlinks (gcc/g++/cc/c++) and PHP extension
    conf.d symlinks, because no maintainer scripts run on overlay install.
  - Strip documentation, then pack with scripts/runtime-bundles/
    create_deterministic_tar.py (root ownership, fixed mtime, sorted
    entries) and compress with zstd level 19.

The resulting file is named conzyx-<component>-arm64-<version>.tar.zst.
Its SHA-256 is printed for the lockfile and the in-code bundle constants.
The app verifies that SHA-256 again before extracting, so a bad bundle
fails loudly instead of installing half a toolchain.

Usage:
  python3 build-cpp-php-from-ubuntu-ports.py --component cpp --version 2026.10.1
  python3 build-cpp-php-from-ubuntu-ports.py --component php --version 2026.10.1
"""

import argparse
import gzip
import hashlib
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

PORTS = "https://ports.ubuntu.com/ubuntu-ports"
SUITE = "focal"
COMPONENTS = {
    "cpp": ["build-essential", "cmake", "gdb", "gcc", "g++", "make"],
    "php": ["php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip", "unzip"],
}
POCKETS = ["", "-updates"]
ARCHES = ["main", "universe"]
COMPOSER_URL = "https://getcomposer.org/composer-stable.phar"


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "conzyx-bundle-builder/1.0"})
    with urllib.request.urlopen(req, timeout=120) as response:
        return response.read()


def parse_packages(blob: bytes) -> dict:
    text = gzip.decompress(blob).decode("utf-8", "replace")
    packages = {}
    current = {}
    for line in text.splitlines():
        if not line.strip():
            if "Package" in current:
                packages[current["Package"]] = current
            current = {}
            continue
        if line.startswith(" ") or line.startswith("\t"):
            continue
        key, _, value = line.partition(":")
        current[key.strip()] = value.strip()
    if "Package" in current:
        packages[current["Package"]] = current
    return packages


def strip_dep(dep: str) -> str:
    dep = dep.strip()
    dep = re.sub(r"\s*\([^)]*\)", "", dep)
    dep = re.sub(r":any$", "", dep)
    return dep.strip()


def split_alternatives(field: str) -> list:
    result = []
    for group in field.split(","):
        group = group.strip()
        if not group:
            continue
        result.append([strip_dep(a) for a in group.split("|") if strip_dep(a)])
    return result


def resolve(seeds: list, index: dict, provides: dict) -> set:
    resolved = set()
    queue = list(seeds)
    missing = []
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


def build_component(component: str, version: str, work: Path, out: Path) -> Path:
    index = {}
    provides: dict = {}
    for pocket in POCKETS:
        for repo in ARCHES:
            url = f"{PORTS}/dists/{SUITE}{pocket}/{repo}/binary-arm64/Packages.gz"
            print(f"index: {url}")
            for name, entry in parse_packages(fetch(url)).items():
                index[name] = entry
    for name, entry in index.items():
        for provided in entry.get("Provides", "").split(","):
            provided = strip_dep(provided)
            if provided:
                provides.setdefault(provided, []).append(name)

    seeds = COMPONENTS[component]
    resolved = resolve(seeds, index, provides)
    print(f"resolved {len(resolved)} packages for {component}")

    debs = work / "debs"
    debs.mkdir(parents=True, exist_ok=True)
    for name in sorted(resolved):
        entry = index[name]
        filename = entry["Filename"]
        dest = debs / Path(filename).name
        if not dest.exists():
            data = fetch(f"{PORTS}/{filename}")
            dest.write_bytes(data)
        digest = hashlib.sha256(dest.read_bytes()).hexdigest()
        if digest != entry["SHA256"].lower():
            raise SystemExit(f"SHA-256 mismatch for {name}: {digest} != {entry['SHA256']}")
        if dest.stat().st_size != int(entry["Size"]):
            raise SystemExit(f"Size mismatch for {name}")

    staging = work / "staging"
    shutil.rmtree(staging, ignore_errors=True)
    staging.mkdir(parents=True)
    for name in sorted(resolved):
        entry = index[name]
        deb = debs / Path(entry["Filename"]).name
        subprocess.run(["dpkg-deb", "-x", str(deb), str(staging)], check=True)

    for sub in ("usr/share/doc", "usr/share/man"):
        shutil.rmtree(staging / sub, ignore_errors=True)

    usr_bin = staging / "usr" / "bin"
    if component == "cpp":
        for tool, versioned in (("gcc", "gcc-9"), ("g++", "g++-9")):
            if (usr_bin / versioned).exists():
                for link in (tool,):
                    target = usr_bin / link
                    if not target.exists() and not target.is_symlink():
                        target.symlink_to(versioned)
        if (usr_bin / "gcc-9").exists() and not (usr_bin / "cc").exists():
            (usr_bin / "cc").symlink_to("gcc-9")
        if (usr_bin / "g++-9").exists() and not (usr_bin / "c++").exists():
            (usr_bin / "c++").symlink_to("g++-9")
        for triplet in ("aarch64-linux-gnu-gcc", "aarch64-linux-gnu-g++"):
            base = "gcc-9" if triplet.endswith("-gcc") else "g++-9"
            if (usr_bin / triplet).exists():
                continue
            if (usr_bin / base).exists():
                (usr_bin / triplet).symlink_to(base)
    else:
        php_bin = None
        for candidate in ("php7.4", "php8.4", "php8.1", "php8.2"):
            if (usr_bin / candidate).exists():
                php_bin = candidate
                break
        if php_bin is None:
            raise SystemExit("No php binary found in extracted packages")
        php_link = usr_bin / "php"
        if not php_link.exists() and not php_link.is_symlink():
            php_link.symlink_to(php_bin)
        php_version = php_bin.replace("php", "")
        # Debian generates mods-available/*.ini files in postinst (they are
        # NOT in the .deb data archives), and phpenmod links them into
        # cli/conf.d. An overlay install runs no maintainer scripts, so
        # replicate both steps here, or no extension would load at all:
        # every extension .so gets a mods-available entry, and cli/conf.d
        # links it in (opcache first, priority 10, like Debian; rest 20).
        ext_dir = staging / "usr" / "lib" / "php" / "20190902"
        mods = staging / "etc" / "php" / php_version / "mods-available"
        confd = staging / "etc" / "php" / php_version / "cli" / "conf.d"
        mods.mkdir(parents=True, exist_ok=True)
        confd.mkdir(parents=True, exist_ok=True)
        if ext_dir.is_dir():
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
        composer = staging / "usr" / "local" / "bin" / "composer"
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

    script_dir = Path(__file__).resolve().parent
    tar_path = work / f"{component}.tar"
    subprocess.run(
        [sys.executable, str(script_dir / "create_deterministic_tar.py"), str(staging), str(tar_path)],
        check=True,
    )
    out.mkdir(parents=True, exist_ok=True)
    final = out / f"conzyx-{component}-arm64-{version}.tar.zst"
    import zstandard

    cctx = zstandard.ZstdCompressor(level=19)
    with open(tar_path, "rb") as source, open(final, "wb") as target:
        with cctx.stream_writer(target) as compressor:
            shutil.copyfileobj(source, compressor, length=1024 * 1024)
    sha256 = hashlib.sha256(final.read_bytes()).hexdigest()
    file_count = sum(1 for _ in staging.rglob("*"))
    print(f"bundle: {final}")
    print(f"size: {final.stat().st_size} bytes compressed, {tar_path.stat().st_size} bytes tar")
    print(f"sha256: {sha256}")
    print(f"entries: {file_count}")
    return final


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--component", choices=["cpp", "php"], required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--work", default="bundle-work")
    parser.add_argument("--out", default="bundle-out")
    args = parser.parse_args()
    build_component(args.component, args.version, Path(args.work), Path(args.out))


if __name__ == "__main__":
    main()
