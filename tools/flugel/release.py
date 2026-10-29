#!/usr/bin/env python3
"""Flugel: package verified APK metadata and atomically publish validated releases."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tarfile
import tempfile
from urllib.parse import urlsplit

APK_NAME = "Flugel-Manga.apk"
MAX_APK_SIZE = 512 * 1024 * 1024
MAX_MANIFEST_SIZE = 16 * 1024


def base_url(value):
    if not isinstance(value, str) or any(character.isspace() or character == "\\" for character in value):
        raise ValueError("Update base URL cannot contain whitespace or backslashes")
    parsed = urlsplit(value)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.query or parsed.fragment):
        raise ValueError("Update base URL must use HTTPS without credentials, query or fragment")
    parsed.port  # Reject invalid ports.
    if any(part in (".", "..") for part in parsed.path.split("/")):
        raise ValueError("Update base URL cannot contain relative path segments")
    return value.rstrip("/")


def validate_manifest(manifest, expected_base_url):
    if not isinstance(manifest, dict):
        raise ValueError("Manifest must be a JSON object")
    code = manifest.get("versionCode")
    size = manifest.get("size")
    if type(code) is not int or not 0 < code <= 2_100_000_000:
        raise ValueError("versionCode must be a positive Android version code")
    if type(size) is not int or not 0 < size <= MAX_APK_SIZE:
        raise ValueError("APK size is outside the allowed range")
    if not isinstance(manifest.get("versionName"), str) or not manifest["versionName"].strip():
        raise ValueError("versionName is required")
    if not isinstance(manifest.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", manifest["sha256"]):
        raise ValueError("A lowercase SHA-256 digest is required")
    expected_url = f"{base_url(expected_base_url)}/releases/{code}/{APK_NAME}"
    if manifest.get("url") != expected_url:
        raise ValueError("APK URL does not match the configured immutable HTTPS path")
    return manifest


def encode_manifest(manifest):
    return (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def package(metadata_path, expected_code, expected_base_url, output):
    metadata_path = Path(metadata_path)
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if metadata.get("variantName") != "flugelRelease":
        raise ValueError("Only the signed flugelRelease variant can be packaged")
    if metadata.get("applicationId") != "app.koharia.flugel":
        raise ValueError("Unexpected personal-build application ID")
    universal = [element for element in metadata["elements"] if not element.get("filters")]
    if len(universal) != 1:
        raise ValueError("Expected exactly one universal release APK")
    element = universal[0]
    apk = metadata_path.parent / element["outputFile"]
    if apk.resolve().parent != metadata_path.parent.resolve() or not apk.is_file():
        raise ValueError("APK must be a file next to output-metadata.json")
    if element["versionCode"] != expected_code:
        raise ValueError("Built APK versionCode does not match this workflow run")
    manifest = validate_manifest({
        "versionCode": element["versionCode"],
        "versionName": element["versionName"],
        "url": f"{base_url(expected_base_url)}/releases/{expected_code}/{APK_NAME}",
        "sha256": sha256_file(apk),
        "size": apk.stat().st_size,
    }, expected_base_url)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(apk, output / APK_NAME)
    manifest_bytes = encode_manifest(manifest)
    (output / "latest.json").write_bytes(manifest_bytes)
    (output / f"{APK_NAME}.sha256").write_text(f"{manifest['sha256']}  {APK_NAME}\n", encoding="ascii")
    with tarfile.open(output / "deploy.tar", "w") as archive:
        archive.add(output / APK_NAME, arcname=APK_NAME)
        archive.add(output / "latest.json", arcname="latest.json")
    return manifest


def publish(stream, root, expected_base_url):
    root = Path(root).resolve()
    root.mkdir(parents=True, exist_ok=True)
    # Staging lives outside the served tree: incomplete uploads are never downloadable.
    with tempfile.TemporaryDirectory(prefix=".flugel-upload-", dir=root.parent) as temporary:
        staging = Path(temporary)
        names = set()
        with tarfile.open(fileobj=stream, mode="r|") as archive:
            for member in archive:
                if member.name not in (APK_NAME, "latest.json") or member.name in names or not member.isfile():
                    raise ValueError("Upload must contain exactly one regular APK and latest.json")
                limit = MAX_APK_SIZE if member.name == APK_NAME else MAX_MANIFEST_SIZE
                if not 0 < member.size <= limit:
                    raise ValueError("Upload member exceeds its size limit")
                names.add(member.name)
                source = archive.extractfile(member)
                with source, (staging / member.name).open("wb") as destination:
                    shutil.copyfileobj(source, destination, 1024 * 1024)
        if names != {APK_NAME, "latest.json"}:
            raise ValueError("Upload is missing its APK or manifest")
        manifest = validate_manifest(json.loads((staging / "latest.json").read_bytes()), expected_base_url)
        apk = staging / APK_NAME
        if apk.stat().st_size != manifest["size"] or sha256_file(apk) != manifest["sha256"]:
            raise ValueError("APK size or SHA-256 verification failed")
        with apk.open("rb") as source:
            if source.read(4) != b"PK\x03\x04":
                raise ValueError("APK must be a ZIP archive")
        # Serialize publishers so concurrent SSH sessions cannot roll latest.json backwards.
        import fcntl
        lock_path = root.parent / f".{root.name}.publish.lock"
        with lock_path.open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            current_path = root / "latest.json"
            if current_path.exists():
                current = validate_manifest(json.loads(current_path.read_bytes()), expected_base_url)
                if manifest["versionCode"] < current["versionCode"]:
                    raise ValueError("Refusing to downgrade the published version")
                if manifest["versionCode"] == current["versionCode"] and manifest != current:
                    raise ValueError("A versionCode cannot be reused for a different release")
            destination = root / "releases" / str(manifest["versionCode"]) / APK_NAME
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists():
                if sha256_file(destination) != manifest["sha256"]:
                    raise ValueError("An immutable APK already exists with different bytes")
            else:
                apk.chmod(0o644)
                os.replace(apk, destination)
            # Publish the manifest last, atomically, after the immutable APK is available.
            ready_manifest = staging / "latest.json"
            ready_manifest.write_bytes(encode_manifest(manifest))
            ready_manifest.chmod(0o644)
            os.replace(ready_manifest, current_path)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    pack = subparsers.add_parser("package")
    pack.add_argument("--metadata", required=True)
    pack.add_argument("--version-code", required=True, type=int)
    pack.add_argument("--base-url", required=True)
    pack.add_argument("--output", required=True)
    serve = subparsers.add_parser("publish")
    serve.add_argument("--root", required=True)
    serve.add_argument("--base-url", required=True)
    serve.add_argument("--ssh", action="store_true")
    args = parser.parse_args()
    try:
        if args.command == "package":
            manifest = package(args.metadata, args.version_code, args.base_url, args.output)
        else:
            if args.ssh and os.environ.get("SSH_ORIGINAL_COMMAND") != "publish":
                raise ValueError("This deployment key only accepts the publish command")
            manifest = publish(sys.stdin.buffer, args.root, args.base_url)
        print(f"Flugel release {manifest['versionCode']}: SHA-256 {manifest['sha256']}")
    except (ValueError, KeyError, OSError, tarfile.TarError) as error:
        parser.exit(1, f"Release rejected: {error}\n")


if __name__ == "__main__":
    main()
