"""Flugel: release packaging and publisher safety tests; no network/server access."""

import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

import release

BASE_URL = "https://flugelserver.tail42f1f4.ts.net:10444"
APK_BYTES = b"PK\x03\x04signed-apk-fixture"


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        artifacts = Path(__file__).resolve().parents[2] / ".test-artifacts" / "flugel-tooling"
        artifacts.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=artifacts)
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.root = self.directory / "served"
        self.build = self.directory / "build"
        self.build.mkdir()
        (self.build / "universal.apk").write_bytes(APK_BYTES)
        self.metadata = self.build / "output-metadata.json"
        self.metadata.write_text(json.dumps({
            "variantName": "flugelRelease", "applicationId": "app.koharia.flugel",
            "elements": [{"filters": [], "outputFile": "universal.apk", "versionCode": 100123,
                          "versionName": "0.7.0"}],
        }))

    def manifest(self, code=100123, apk=APK_BYTES):
        return {"versionCode": code, "versionName": "0.7.0",
                "url": f"{BASE_URL}/releases/{code}/{release.APK_NAME}",
                "size": len(apk), "sha256": hashlib.sha256(apk).hexdigest()}

    def upload(self, manifest=None, apk=APK_BYTES, extra=None):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w") as archive:
            for name, data in ((release.APK_NAME, apk), ("latest.json", release.encode_manifest(manifest or self.manifest()))):
                member = tarfile.TarInfo(name)
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
            if extra:
                archive.addfile(extra, io.BytesIO(b""))
        stream.seek(0)
        return stream

    def test_package_selects_universal_apk_and_emits_verified_metadata(self):
        output = self.directory / "package"
        manifest = release.package(self.metadata, 100123, BASE_URL, output)
        self.assertEqual(self.manifest(), manifest)
        self.assertEqual(APK_BYTES, (output / release.APK_NAME).read_bytes())
        self.assertEqual(manifest, json.loads((output / "latest.json").read_bytes()))
        with (output / "deploy.tar").open("rb") as archive:
            release.publish(archive, self.root, BASE_URL)
        self.assertEqual(APK_BYTES, (self.root / "releases/100123" / release.APK_NAME).read_bytes())
        self.assertEqual(manifest, json.loads((self.root / "latest.json").read_bytes()))

    def test_package_rejects_wrong_flavor_missing_universal_and_wrong_run_version(self):
        original = json.loads(self.metadata.read_bytes())
        invalid = [dict(original, variantName="standardRelease"), dict(original, applicationId="app.koharia"),
                   dict(original, elements=[]), dict(original, elements=original["elements"] * 2)]
        for metadata in invalid:
            with self.subTest(metadata=metadata):
                self.metadata.write_text(json.dumps(metadata))
                with self.assertRaises(ValueError):
                    release.package(self.metadata, 100123, BASE_URL, self.directory / "package")
        self.metadata.write_text(json.dumps(original))
        with self.assertRaises(ValueError):
            release.package(self.metadata, 100124, BASE_URL, self.directory / "package")

    def test_repeated_identical_upload_is_idempotent(self):
        for _ in range(2):
            self.assertEqual(self.manifest(), release.publish(self.upload(), self.root, BASE_URL))

    def test_tampered_apk_never_replaces_published_manifest(self):
        release.publish(self.upload(), self.root, BASE_URL)
        before = (self.root / "latest.json").read_bytes()
        with self.assertRaises(ValueError):
            release.publish(self.upload(manifest=self.manifest(100124), apk=APK_BYTES + b"tampered"), self.root, BASE_URL)
        self.assertEqual(before, (self.root / "latest.json").read_bytes())
        self.assertFalse((self.root / "releases/100124").exists())

    def test_valid_higher_version_is_published_and_lower_version_is_rejected(self):
        release.publish(self.upload(manifest=self.manifest(100124)), self.root, BASE_URL)
        with self.assertRaises(ValueError):
            release.publish(self.upload(), self.root, BASE_URL)
        self.assertEqual(100124, json.loads((self.root / "latest.json").read_bytes())["versionCode"])

    def test_version_code_cannot_be_reused_with_different_valid_bytes(self):
        release.publish(self.upload(), self.root, BASE_URL)
        other_apk = APK_BYTES + b"different"
        with self.assertRaises(ValueError):
            release.publish(self.upload(manifest=self.manifest(apk=other_apk), apk=other_apk), self.root, BASE_URL)
        self.assertEqual(APK_BYTES, (self.root / "releases/100123" / release.APK_NAME).read_bytes())

    def test_manifest_rejects_http_foreign_hosts_queries_missing_hash_and_bad_size(self):
        invalid = [dict(self.manifest(), url="http://example.test/app.apk"),
                   dict(self.manifest(), url="https://example.test/app.apk"),
                   dict(self.manifest(), url=self.manifest()["url"] + "?token=secret"),
                   dict(self.manifest(), sha256=""), dict(self.manifest(), sha256="x" * 64),
                   dict(self.manifest(), size=0), dict(self.manifest(), size=True),
                   dict(self.manifest(), versionCode=True), dict(self.manifest(), versionName="")]
        for manifest in invalid:
            with self.subTest(manifest=manifest), self.assertRaises(ValueError):
                release.publish(self.upload(manifest=manifest), self.root, BASE_URL)
        self.assertFalse((self.root / "latest.json").exists())

    def test_tar_rejects_traversal_symlinks_duplicates_and_excess_files(self):
        invalid = [tarfile.TarInfo("../escape"), tarfile.TarInfo("/absolute"),
                   tarfile.TarInfo("latest.json"), tarfile.TarInfo("extra")]
        link = tarfile.TarInfo("linked")
        link.type = tarfile.SYMTYPE
        link.linkname = "/etc/passwd"
        invalid.append(link)
        for member in invalid:
            with self.subTest(name=member.name), self.assertRaises(ValueError):
                release.publish(self.upload(extra=member), self.root, BASE_URL)
        self.assertFalse((self.root / "latest.json").exists())
        self.assertFalse((self.directory / "escape").exists())
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w") as archive:
            link.name = release.APK_NAME
            archive.addfile(link)
        stream.seek(0)
        with self.assertRaises(ValueError):
            release.publish(stream, self.root, BASE_URL)

    def test_missing_apk_is_rejected(self):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w") as archive:
            data = release.encode_manifest(self.manifest())
            member = tarfile.TarInfo("latest.json")
            member.size = len(data)
            archive.addfile(member, io.BytesIO(data))
        stream.seek(0)
        with self.assertRaises(ValueError):
            release.publish(stream, self.root, BASE_URL)
        self.assertFalse((self.root / "latest.json").exists())

    def test_non_zip_apk_is_rejected_even_when_its_hash_matches(self):
        bad_apk = b"not an APK"
        with self.assertRaises(ValueError):
            release.publish(self.upload(manifest=self.manifest(apk=bad_apk), apk=bad_apk), self.root, BASE_URL)
        self.assertFalse((self.root / "latest.json").exists())


if __name__ == "__main__":
    unittest.main()
