import importlib.util
import plistlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "apple_package", Path(__file__).resolve().parents[1] / "validate-apple-package.py"
)
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


class ApplePackageTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.app = self.root / "Recly.app"
        self.dsyms = self.root / "dSYMs"
        self.identities = {}
        self.addCleanup(patch.stopall)
        patch.object(package, "uuids", side_effect=lambda path: self.identities.get(path, set())).start()
        self.nm = patch.object(package, "run", return_value="00000100 t _kfun:recly.core.ReclyCore#disconnect(){}").start()

    def bundle(self, path, identities, dsym_name, mac=False):
        contents = path / "Contents" if mac else path
        resources = contents / "Resources" if mac else contents
        executable = contents / "MacOS/Recly" if mac else contents / "Recly"
        resources.mkdir(parents=True)
        executable.parent.mkdir(parents=True, exist_ok=True)
        executable.touch()
        (contents / "Info.plist").write_bytes(plistlib.dumps({
            "CFBundleExecutable": "Recly", "CFBundleIdentifier": "app.recly.test"
        }))
        (resources / "PrivacyInfo.xcprivacy").write_bytes(plistlib.dumps({"NSPrivacyAccessedAPITypes": []}))
        dwarf = self.dsyms / dsym_name / "Contents/Resources/DWARF/Recly"
        dwarf.parent.mkdir(parents=True)
        dwarf.touch()
        self.identities[executable] = identities
        self.identities[dwarf] = identities
        return dwarf

    def test_phone_and_watch_resolve_duplicate_executable_names_by_uuid(self):
        self.bundle(self.app, {("PHONE", "arm64")}, "Recly.app 1.dSYM")
        self.bundle(self.app / "Watch/Recly.app", {("WATCH64", "arm64"), ("WATCH32", "arm64_32")}, "Recly.app.dSYM")
        package.validate(self.app, self.dsyms)
        self.assertEqual(self.nm.call_count, 3)

    def test_mac_resource_and_executable_layout(self):
        self.bundle(self.app, {("MAC", "arm64")}, "Recly.app.dSYM", mac=True)
        package.validate(self.app, self.dsyms)

    def test_embedded_core_stub_in_watch_is_rejected(self):
        self.bundle(self.app, {("PHONE", "arm64")}, "Recly.app.dSYM")
        (self.app / "Watch/Recly.app/Frameworks/ReclyCore.framework").mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, "not embedded"):
            package.validate(self.app, self.dsyms)

    def test_missing_privacy_manifest_is_rejected(self):
        self.bundle(self.app, {("PHONE", "arm64")}, "Recly.app.dSYM")
        (self.app / "PrivacyInfo.xcprivacy").unlink()
        with self.assertRaises(FileNotFoundError):
            package.validate(self.app, self.dsyms)

    def test_mismatched_or_missing_architecture_in_dsym_is_rejected(self):
        dwarf = self.bundle(self.app, {("PHONE", "arm64"), ("WATCH", "arm64_32")}, "Recly.app.dSYM")
        for identities in [set(), {("OTHER", "arm64")}, {("PHONE", "arm64")}]:
            with self.subTest(identities=identities), self.assertRaisesRegex(ValueError, "No matching app dSYM"):
                self.identities[dwarf] = identities
                package.validate(self.app, self.dsyms)

    def test_matching_dsym_without_core_symbols_is_rejected(self):
        self.bundle(self.app, {("PHONE", "arm64")}, "Recly.app.dSYM")
        self.nm.return_value = "00000100 T _main"
        with self.assertRaisesRegex(ValueError, "missing shared core symbols"):
            package.validate(self.app, self.dsyms)

    def test_missing_app_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Missing app bundle"):
            package.validate(self.app, self.dsyms)
