#!/usr/bin/env python3
"""
Automated client run test for Aeonis.

Boots Minecraft 26.3 + Fabric Loader through a real PolyMC instance: the
instance's own libraries, assets and mods folder are used, so the test exercises
the exact runtime the mod ships against rather than a Loom development
classpath. The launcher metadata PolyMC already downloaded (meta/, libraries/,
assets/) is reused, which means no network access and no accounts are needed.

The run is considered a pass when the client reaches the main menu (the OpenAL
/ sound-engine startup lines) without emitting a crash report, a mixin apply
error, or an entrypoint failure. The client is killed as soon as a verdict is
reached, so a run takes well under a minute in the happy case.

Usage:
    python3 scripts/mc-smoke-test.py                  # build, sync, launch, check
    python3 scripts/mc-smoke-test.py --no-build       # reuse existing jar
    python3 scripts/mc-smoke-test.py --no-sync        # don't copy jars into mods
    python3 scripts/mc-smoke-test.py --dry-run        # print the launch command
    python3 scripts/mc-smoke-test.py --timeout 300    # longer budget

Exit status: 0 = reached main menu, 1 = crashed / timed out / harness error.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

DEFAULT_POLYMC_HOME = Path(
    "~/.var/app/org.polymc.PolyMC/data/PolyMC"
).expanduser()
DEFAULT_INSTANCE = "26.3 Fabric"

# Jar(s) produced by the Fabric module that get copied into the instance.
FABRIC_LIBS = REPO_ROOT / "fabric" / "build" / "libs"

# Lines that mean "the client is up and showing the main menu".
SUCCESS_MARKERS = (
    "Sound engine started",
    "OpenAL initialized",
    "OpenAL Soft",
    "Narrator library for x64 successfully loaded",
    "Created: 1024x512x4 minecraft:textures/atlas/blocks.png-atlas",
)

# Lines that mean the client died. Keep these specific: generic "Exception"
# matches show up in benign shutdown paths.
FAILURE_MARKERS = (
    "Minecraft Crash Report",
    "Mixin apply for mod aeonis failed",
    "Mixin transformation of",
    "MixinTransformerError",
    "MixinApplyError",
    "InvalidInjectionException",
    "InvalidMixinException",
    "Could not execute entrypoint stage",
    "A fatal error has been detected by the Java Runtime Environment",
    "Failed to start game",
    "java.lang.NoClassDefFoundError",
    "java.lang.NoSuchMethodError",
    "java.lang.NoSuchFieldError",
    "java.lang.AbstractMethodError",
    "java.lang.IncompatibleClassChangeError",
)

OS_ALIASES = {"mac": "osx", "darwin": "osx", "win": "windows"}


class HarnessError(RuntimeError):
    """Something about the local setup is wrong (not a mod failure)."""


# --------------------------------------------------------------------------- #
# PolyMC metadata
# --------------------------------------------------------------------------- #


def maven_to_path(polymc_home: Path, coord: str) -> Path:
    """Resolve a `group:artifact:version[:classifier]` coordinate to a file."""
    parts = coord.split(":")
    if len(parts) < 3:
        raise HarnessError(f"malformed library coordinate: {coord!r}")
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = parts[3] if len(parts) > 3 else None
    filename = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "")
    return (
        polymc_home
        / "libraries"
        / Path(*group.split("."))
        / artifact
        / version
        / f"{filename}.jar"
    )


def rules_allow(library: dict, os_name: str, os_arch: str) -> bool:
    """Evaluate a Mojang library `rules` block. No rules means allowed."""
    rules = library.get("rules")
    if not rules:
        return True
    allowed = False
    for rule in rules:
        action = rule.get("action")
        applies = True
        if "os" in rule:
            wanted_os = OS_ALIASES.get(rule["os"].get("name", ""), rule["os"].get("name"))
            if wanted_os and wanted_os != os_name:
                applies = False
            wanted_arch = rule["os"].get("arch")
            if applies and wanted_arch and wanted_arch != os_arch:
                applies = False
        if applies:
            allowed = action == "allow"
    return allowed


def read_instance_pack(instance_dir: Path) -> dict:
    pack = instance_dir / "mmc-pack.json"
    if not pack.is_file():
        raise HarnessError(f"not a PolyMC instance (no mmc-pack.json): {instance_dir}")
    return json.loads(pack.read_text())


def component_version(components: list[dict], uid: str) -> str | None:
    for component in components:
        if component.get("uid") == uid:
            return component.get("version")
    return None


def build_classpath(polymc_home: Path, instance_dir: Path):
    """Return (classpath entries, natives jars, launch metadata) for the instance."""
    meta = polymc_home / "meta"
    pack = read_instance_pack(instance_dir)
    components = pack.get("components", [])

    mc_version = component_version(components, "net.minecraft")
    loader_version = component_version(components, "net.fabricmc.fabric-loader")
    lwjgl_version = component_version(components, "org.lwjgl3")
    if not mc_version or not loader_version:
        raise HarnessError("instance is missing Minecraft or Fabric Loader components")

    mc_json = json.loads((meta / "net.minecraft" / f"{mc_version}.json").read_text())
    loader_json = json.loads(
        (meta / "net.fabricmc.fabric-loader" / f"{loader_version}.json").read_text()
    )

    # Mojang's tiny LWJGL component manifest is what supplies the natives.
    lwjgl_json = {"libraries": []}
    if lwjgl_version:
        lwjgl_path = meta / "org.lwjgl3" / f"{lwjgl_version}.json"
        if lwjgl_path.is_file():
            lwjgl_json = json.loads(lwjgl_path.read_text())

    machine = platform.machine().lower()
    os_arch = {
        "x86_64": "x86_64",
        "amd64": "x86_64",
        "aarch64": "arm64",
        "arm64": "arm64",
    }.get(machine, machine)
    os_name = {"linux": "linux", "darwin": "osx", "windows": "windows"}.get(
        sys.platform if sys.platform in ("linux", "darwin", "windows") else "linux",
        "linux",
    )

    classpath: list[Path] = []
    natives: list[Path] = []
    missing: list[str] = []

    def add(library: dict) -> None:
        if not rules_allow(library, os_name, os_arch):
            return
        coord = library["name"]
        path = maven_to_path(polymc_home, coord)
        if not path.is_file():
            missing.append(coord)
            return
        if "natives" in coord:
            natives.append(path)
        else:
            classpath.append(path)

    # Game jar last-ish is fine; Loader searches the whole classpath for it.
    main_jar = mc_json.get("mainJar")
    if main_jar:
        add(main_jar)
    else:
        classpath.append(
            polymc_home
            / "libraries"
            / "com"
            / "mojang"
            / "minecraft"
            / mc_version
            / f"minecraft-{mc_version}-client.jar"
        )

    for library in mc_json.get("libraries", []):
        add(library)
    for library in loader_json.get("libraries", []):
        add(library)
    for library in lwjgl_json.get("libraries", []):
        add(library)

    # Missing entries are normally macOS/Windows-only artifacts that the rules
    # already filtered out; only complain about ones we actually wanted.
    if missing:
        raise HarnessError(
            "PolyMC library cache is incomplete:\n  - " + "\n  - ".join(sorted(missing))
        )

    info = {
        "mc_version": mc_version,
        "loader_version": loader_version,
        "main_class": loader_json.get("mainClass", "net.fabricmc.loader.impl.launch.knot.KnotClient"),
        "assets_dir": polymc_home / "assets",
        "asset_index": str(mc_json.get("assetIndex", {}).get("id", "")),
        "game_args": mc_json.get("minecraftArguments", ""),
    }
    return classpath, natives, info


def extract_natives(natives: list[Path], target: Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    for jar in natives:
        try:
            with zipfile.ZipFile(jar) as archive:
                for entry in archive.namelist():
                    if entry.endswith("/") or entry.startswith("META-INF/"):
                        continue
                    archive.extract(entry, target)
        except zipfile.BadZipFile as exc:
            raise HarnessError(f"corrupt natives jar {jar}: {exc}") from exc


# --------------------------------------------------------------------------- #
# Mod jar sync
# --------------------------------------------------------------------------- #


def find_mod_jars() -> list[Path]:
    if not FABRIC_LIBS.is_dir():
        raise HarnessError(f"no built jar: {FABRIC_LIBS} does not exist")
    jars = sorted(
        path
        for path in FABRIC_LIBS.glob("*.jar")
        if "sources" not in path.name and "dev" not in path.stem
    )
    if not jars:
        raise HarnessError(f"no mod jar in {FABRIC_LIBS}")
    return jars


def sync_jars(jars: list[Path], mods_dir: Path) -> list[str]:
    """Copy `jars` into the instance mods folder, replacing older builds."""
    mods_dir.mkdir(parents=True, exist_ok=True)
    replaced: list[str] = []
    stems = {jar.name.rsplit("-", 1)[0] for jar in jars}
    for existing in mods_dir.glob("*.jar"):
        if existing.name.rsplit("-", 1)[0] in stems:
            replaced.append(existing.name)
            existing.unlink()
    for jar in jars:
        shutil.copy2(jar, mods_dir / jar.name)
    return replaced


# --------------------------------------------------------------------------- #
# Log watching
# --------------------------------------------------------------------------- #


class LogTail:
    """Reads a log file that the game may rotate or truncate underneath us."""

    def __init__(self, path: Path):
        self.path = path
        self._offset = 0
        self._identity = None
        self._buffer = ""

    def poll(self) -> str:
        try:
            stat = self.path.stat()
        except FileNotFoundError:
            return ""
        identity = (stat.st_dev, stat.st_ino)
        if identity != self._identity or stat.st_size < self._offset:
            self._identity = identity
            self._offset = 0
            self._buffer = ""
        try:
            with self.path.open("r", errors="replace") as handle:
                handle.seek(self._offset)
                chunk = handle.read()
                self._offset = handle.tell()
        except OSError:
            return ""
        self._buffer += chunk
        return chunk


def first_match(text: str, markers) -> str | None:
    for marker in markers:
        if marker in text:
            return marker
    return None


def describe_failure(log_text: str) -> list[str]:
    """Pull the useful lines out of a crash log for the summary."""
    lines = log_text.splitlines()
    interesting: list[str] = []
    for index, line in enumerate(lines):
        if any(
            token in line
            for token in (
                "InvalidInjectionException",
                "MixinApplyError",
                "MixinTransformerError",
                "InvalidMixinException",
                "Could not execute entrypoint",
                "Caused by:",
                "Exception in thread",
            )
        ):
            interesting.append(line.strip())
            if len(interesting) >= 6:
                break
    crash_match = re.search(
        r"Description: (.+)", log_text
    )
    if crash_match:
        interesting.insert(0, f"crash description: {crash_match.group(1).strip()}")
    return interesting or [line.strip() for line in lines if line.strip()][-5:]


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #


def build_args(info: dict, game_dir: Path, width: int, height: int) -> list[str]:
    return [
        "--username", "Dev",
        "--version", info["mc_version"],
        "--gameDir", str(game_dir),
        "--assetsDir", str(info["assets_dir"]),
        "--assetIndex", info["asset_index"],
        "--uuid", "00000000000000000000000000000000",
        "--accessToken", "0",
        "--userType", "legacy",
        "--versionType", "release",
        "--width", str(width),
        "--height", str(height),
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description="Aeonis client run test")
    parser.add_argument("--instance", default=os.environ.get("AEONIS_INSTANCE", DEFAULT_INSTANCE))
    parser.add_argument(
        "--polymc-home",
        default=os.environ.get("POLYMC_HOME", str(DEFAULT_POLYMC_HOME)),
        help="PolyMC data directory holding meta/, libraries/ and assets/",
    )
    parser.add_argument("--java", default=os.environ.get("AEONIS_TEST_JAVA", "java"))
    parser.add_argument("--timeout", type=float, default=240.0, help="seconds to wait for a verdict")
    parser.add_argument("--width", type=int, default=854)
    parser.add_argument("--height", type=int, default=480)
    parser.add_argument("--no-build", action="store_true", help="skip ./gradlew :fabric:build")
    parser.add_argument("--no-sync", action="store_true", help="skip copying jars into mods/")
    parser.add_argument("--dry-run", action="store_true", help="print the command and exit")
    parser.add_argument("--verbose", action="store_true", help="echo the game log as it arrives")
    args = parser.parse_args()

    polymc_home = Path(args.polymc_home).expanduser()
    instance_dir = polymc_home / "instances" / args.instance
    game_dir = instance_dir / ".minecraft"
    if not game_dir.is_dir():
        raise HarnessError(f"instance game directory not found: {game_dir}")
    if not (polymc_home / "meta").is_dir():
        raise HarnessError(f"PolyMC data directory looks wrong: {polymc_home}")
    if not (os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")):
        print("warning: no DISPLAY/WAYLAND_DISPLAY set; the client needs a screen")

    print(f"instance   : {instance_dir}")
    print(f"game dir   : {game_dir}")

    if not args.no_build:
        print("building   : ./gradlew :fabric:build")
        result = subprocess.run(
            ["./gradlew", ":fabric:build", "--console=plain", "-q"],
            cwd=REPO_ROOT,
        )
        if result.returncode != 0:
            print("BUILD FAILED")
            return 1

    jars = find_mod_jars()
    if not args.no_sync:
        replaced = sync_jars(jars, game_dir / "mods")
        for name in replaced:
            print(f"replaced   : mods/{name}")
        for jar in jars:
            print(f"installed  : mods/{jar.name} ({jar.stat().st_size} bytes)")

    classpath, natives, info = build_classpath(polymc_home, instance_dir)
    print(f"minecraft  : {info['mc_version']} / Fabric Loader {info['loader_version']}")
    print(f"classpath  : {len(classpath)} jars, {len(natives)} native jars")

    if args.dry_run:
        print("\njava -Djava.library.path=<natives> -cp <classpath> "
              f"{info['main_class']} " + " ".join(build_args(info, game_dir, args.width, args.height)))
        return 0

    workdir = Path(tempfile.mkdtemp(prefix="aeonis-smoke-"))
    native_dir = workdir / "natives"
    extract_natives(natives, native_dir)
    stdout_log = workdir / "client-stdout.log"
    latest_log = game_dir / "logs" / "latest.log"
    if latest_log.exists():
        latest_log.unlink()

    command = [
        args.java,
        "-Xmx4G",
        f"-Djava.library.path={native_dir}",
        "-Dminecraft.launcher.brand=poly_mc",
        "-Dminecraft.launcher.version=aeonis-smoke-test",
        "-cp",
        os.pathsep.join(str(path) for path in classpath),
        info["main_class"],
        *build_args(info, game_dir, args.width, args.height),
    ]

    print(f"launching  : {len(' '.join(command))} char command, log -> {stdout_log}")
    with stdout_log.open("w") as sink:
        process = subprocess.Popen(
            command,
            cwd=str(game_dir),
            stdout=sink,
            stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL,
            start_new_session=True,
        )

    stdout_tail = LogTail(stdout_log)
    game_tail = LogTail(latest_log)
    verdict: str | None = None
    reason: str | None = None
    collected = ""
    deadline = time.time() + args.timeout

    try:
        while True:
            for tail in (game_tail, stdout_tail):
                chunk = tail.poll()
                if not chunk:
                    continue
                collected += chunk
                if args.verbose:
                    sys.stdout.write(chunk)
                    sys.stdout.flush()
                failure = first_match(chunk, FAILURE_MARKERS)
                if failure and verdict is None:
                    verdict, reason = "FAIL", failure
                success = first_match(chunk, SUCCESS_MARKERS)
                if success and verdict is None:
                    verdict, reason = "PASS", success
            if verdict:
                break
            if process.poll() is not None:
                verdict, reason = "FAIL", f"client exited early (exit code {process.returncode})"
                break
            if time.time() > deadline:
                verdict, reason = "FAIL", f"timed out after {args.timeout:.0f}s"
                break
            time.sleep(0.5)
    finally:
        if process.poll() is None:
            try:
                os.killpg(os.getpgid(process.pid), signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(os.getpgid(process.pid), signal.SIGKILL)
                except ProcessLookupError:
                    pass
        # Give the game a moment to flush its crash report.
        collected += game_tail.poll()
        collected += stdout_tail.poll()

    print()
    print("=" * 68)
    if verdict == "PASS":
        print(f"RESULT: PASS - reached the main menu ({reason})")
        print(f"full log : {latest_log}")
        return 0

    print(f"RESULT: FAIL - {reason}")
    for line in describe_failure(collected):
        print(f"  {line}")
    crash_reports = sorted((game_dir / "crash-reports").glob("crash-*.txt"))
    if crash_reports:
        print(f"crash report: {crash_reports[-1]}")
    print(f"full log : {latest_log}")
    print(f"stdout   : {stdout_log}")
    print("=" * 68)
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except HarnessError as error:
        print(f"harness error: {error}", file=sys.stderr)
        sys.exit(2)
    except KeyboardInterrupt:
        sys.exit(130)
