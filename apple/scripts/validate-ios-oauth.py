#!/usr/bin/env python3
"""Reject an App Store archive whose compiled Google sign-in configuration cannot work."""

import plistlib
import re
import sys
from pathlib import Path


def validate(info: dict) -> None:
    if not isinstance(info, dict):
        raise ValueError("Compiled Info.plist is not a dictionary")
    client = info.get("GIDClientID")
    if not isinstance(client, str) or not re.fullmatch(
        r"[0-9]+-[a-zA-Z0-9-]+\.apps\.googleusercontent\.com", client
    ) or any(word in client.lower() for word in ("placeholder", "replace", "your-client")):
        raise ValueError("Missing or placeholder Google iOS client ID in the compiled app")
    callback = "com.googleusercontent.apps." + client.removesuffix(".apps.googleusercontent.com")
    url_types = info.get("CFBundleURLTypes", [])
    if not isinstance(url_types, list):
        raise ValueError("Missing Google callback URL scheme")
    schemes = [scheme for item in url_types if isinstance(item, dict)
               if isinstance(item.get("CFBundleURLSchemes"), list)
               for scheme in item["CFBundleURLSchemes"]]
    if callback not in schemes:
        raise ValueError("Google callback URL scheme does not match the compiled client ID")


def validate_archive(archive: Path) -> None:
    apps = list((archive / "Products/Applications").glob("*.app/Info.plist"))
    if len(apps) != 1:
        raise ValueError("Expected exactly one iPhone app in the archive")
    with apps[0].open("rb") as file:
        validate(plistlib.load(file))


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError("Usage: validate-ios-oauth.py <archive.xcarchive>")
        validate_archive(Path(sys.argv[1]))
    except (ValueError, OSError, plistlib.InvalidFileException):
        # Do not print configuration values or parser excerpts from the signed app.
        print("release-ios: invalid Google sign-in configuration in archive; check GIDClientID and callback scheme.", file=sys.stderr)
        sys.exit(1)
    print("release-ios: compiled Google sign-in configuration passed")
