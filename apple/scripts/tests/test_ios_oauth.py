import importlib.util
import plistlib
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "ios_oauth", Path(__file__).resolve().parents[1] / "validate-ios-oauth.py"
)
oauth = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oauth)


class ArchiveOAuthTests(unittest.TestCase):
    def valid(self):
        return {"GIDClientID": "123456-testclient.apps.googleusercontent.com",
                "CFBundleURLTypes": [{"CFBundleURLSchemes": ["com.googleusercontent.apps.123456-testclient"]}]}

    def test_valid_configuration(self):
        oauth.validate(self.valid())

    def test_placeholder_unresolved_empty_and_malformed_ids(self):
        for value in [None, "", "$(GOOGLE_IOS_CLIENT_ID)", "YOUR_CLIENT_ID", "123-placeholder.apps.googleusercontent.com", "client.apps.googleusercontent.com"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                info = self.valid()
                info["GIDClientID"] = value
                oauth.validate(info)

    def test_missing_or_mismatched_callback(self):
        for urls in [[], [{"CFBundleURLSchemes": ["com.googleusercontent.apps.another-client"]}]]:
            with self.subTest(urls=urls), self.assertRaises(ValueError):
                info = self.valid()
                info["CFBundleURLTypes"] = urls
                oauth.validate(info)

    def test_reads_compiled_binary_plist_and_ignores_embedded_watch(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory)
            app = archive / "Products/Applications/Recly.app"
            app.mkdir(parents=True)
            (app / "Info.plist").write_bytes(plistlib.dumps(self.valid(), fmt=plistlib.FMT_BINARY))
            watch = app / "Watch/Recly Watch.app"
            watch.mkdir(parents=True)
            (watch / "Info.plist").write_bytes(plistlib.dumps({}))
            oauth.validate_archive(archive)

    def test_missing_archive_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            oauth.validate_archive(Path(directory))
