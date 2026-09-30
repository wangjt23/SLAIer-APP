import hashlib
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from importlib.util import module_from_spec, spec_from_file_location

spec = spec_from_file_location("feed", Path(__file__).with_name("build-update-manifest.py"))
feed = module_from_spec(spec)
spec.loader.exec_module(feed)


class UpdateManifestTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.assets = Path(self.directory.name)
        self.name = "slaier-1.2.0.apk"
        apk = self.assets / self.name
        apk.write_bytes(b"synthetic APK bytes for metadata/checksum tests")
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        (self.assets / "SHA256SUMS.txt").write_text(f"{digest}  {self.name}\n")
        self.release = dict(tag_name="v1.2.0", draft=False, prerelease=False,
                            published_at="2026-09-30T00:00:00Z", body="Trial notes", assets=[dict(
                                name=self.name, size=apk.stat().st_size,
                                browser_download_url=f"https://github.com/wangjt23/SLAIer-APP/releases/download/v1.2.0/{self.name}")])
        self.badging = "package: name='com.slai.campus' versionCode='9' versionName='1.2.0'\n"

    def build(self):
        with patch.object(feed.subprocess, "run", return_value=SimpleNamespace(stdout=self.badging)):
            return feed.build_manifest(self.release, self.assets, "aapt")

    def test_public_release_has_apk_version_code(self):
        result = self.build()
        self.assertEqual(result["versionCode"], 9)
        self.assertEqual(result["versionName"], "1.2.0")
        self.assertEqual(result["notes"], "Trial notes")

    def test_draft_and_prerelease_are_never_advertised(self):
        for key in ("draft", "prerelease"):
            self.release[key] = True
            with self.assertRaises(ValueError):
                self.build()
            self.release[key] = False

    def test_bad_checksum_is_rejected(self):
        (self.assets / "SHA256SUMS.txt").write_text("0" * 64 + f"  {self.name}\n")
        with self.assertRaises(ValueError):
            self.build()

    def test_wrong_package_or_version_is_rejected(self):
        for metadata in ("package: name='evil.package' versionCode='9' versionName='1.2.0'",
                         "package: name='com.slai.campus' versionCode='9' versionName='1.1.4'"):
            self.badging = metadata
            with self.assertRaises(ValueError):
                self.build()

    def test_asset_size_must_match_download(self):
        self.release["assets"][0]["size"] = 1
        with self.assertRaises(ValueError):
            self.build()


if __name__ == "__main__":
    unittest.main()
