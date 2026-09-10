#!/usr/bin/env python3
"""Check static core packaging, privacy manifests, and matching release symbols."""

import argparse
import plistlib
import re
import subprocess
import sys
from pathlib import Path


def run(*arguments: str) -> str:
    return subprocess.run(arguments, check=True, capture_output=True, text=True).stdout


def uuids(binary: Path) -> set[tuple[str, str]]:
    output = run("xcrun", "dwarfdump", "--uuid", str(binary))
    return set(re.findall(r"UUID: ([0-9A-Fa-f-]+) \(([^)]+)\)", output))


def validate(app: Path, dsym_directory: Path) -> None:
    if not app.is_dir():
        raise ValueError("Missing app bundle")
    # ReclyCore has no resources. Its code and symbols belong to the linked app;
    # embedding it makes Xcode synthesize a codeless dylib without a matching dSYM.
    if any(app.rglob("ReclyCore.framework")):
        raise ValueError("ReclyCore.framework must be linked statically, not embedded")

    symbols = {}
    for dwarf in dsym_directory.glob("*.dSYM/Contents/Resources/DWARF/*"):
        if dwarf.is_file():
            for identity in uuids(dwarf):
                symbols[identity] = dwarf

    for bundle in [app, *app.glob("Watch/*.app")]:
        is_mac = (bundle / "Contents/Info.plist").is_file()
        contents = bundle / "Contents" if is_mac else bundle
        with (contents / "Info.plist").open("rb") as file:
            info = plistlib.load(file)
        resources = contents / "Resources" if is_mac else contents
        with (resources / "PrivacyInfo.xcprivacy").open("rb") as file:
            privacy = plistlib.load(file)
        if not isinstance(privacy, dict) or "NSPrivacyAccessedAPITypes" not in privacy:
            raise ValueError("Missing app privacy API declarations")

        executable = contents / "MacOS" if is_mac else contents
        identities = uuids(executable / info["CFBundleExecutable"])
        if not identities:
            raise ValueError("App executable has no build UUID")
        for identity in sorted(identities):
            dwarf = symbols.get(identity)
            if dwarf is None:
                raise ValueError(f"No matching app dSYM for {info['CFBundleIdentifier']} ({identity[1]})")
            output = run("xcrun", "nm", "-arch", identity[1], str(dwarf))
            if "kfun:recly.core." not in output:
                raise ValueError("Matching app dSYM is missing shared core symbols")
        print(f"apple-package: {info['CFBundleIdentifier']}: privacy manifest and core dSYM verified ({len(identities)} architecture(s))")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    parser.add_argument("--dsym-directory", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        validate(arguments.app, arguments.dsym_directory)
    except (ValueError, KeyError, OSError, plistlib.InvalidFileException, subprocess.CalledProcessError) as error:
        # Do not print subprocess output, which may include local configuration.
        print(f"apple-package: validation failed ({type(error).__name__}): check static embedding, app privacy manifests, and matching core dSYMs", file=sys.stderr)
        sys.exit(1)
