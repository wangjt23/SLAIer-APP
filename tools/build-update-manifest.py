"""Generate the static feed from a published Release and its actual signed APK."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


def build_manifest(release, assets_dir, aapt):
    if release.get("draft") is not False or release.get("prerelease") is not False:
        raise ValueError("Only public stable releases may enter the update feed")
    tag = release["tag_name"]
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise ValueError("Invalid release tag")
    version = tag[1:]
    name = f"slaier-{version}.apk"
    asset = next(a for a in release["assets"] if a["name"] == name)
    url = f"https://github.com/wangjt23/SLAIer-APP/releases/download/{tag}/{name}"
    if asset["browser_download_url"] != url:
        raise ValueError("Unexpected APK download URL")
    apk = assets_dir / name
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    checksums = (assets_dir / "SHA256SUMS.txt").read_text()
    hashes = {}
    for line in checksums.splitlines():
        parts = line.split(maxsplit=1)
        if len(parts) == 2:
            hashes[Path(parts[1].lstrip("*")).name] = parts[0].lower()
    if hashes.get(name) != digest:
        raise ValueError("Release checksum does not match its APK")
    badging = subprocess.run([aapt, "dump", "badging", str(apk)],
                             check=True, capture_output=True, text=True).stdout
    package = next(line for line in badging.splitlines() if line.startswith("package:"))
    fields = dict(re.findall(r"(\w+)='([^']*)'", package))
    if fields.get("name") != "com.slai.campus" or fields.get("versionName") != version:
        raise ValueError("APK package/version does not match release")
    code = int(fields["versionCode"])
    if code <= 0 or apk.stat().st_size != asset["size"]:
        raise ValueError("Invalid versionCode or APK size")
    return dict(tagName=tag, versionName=version, versionCode=code,
                notes=release.get("body") or "", publishedAt=release["published_at"],
                apkUrl=url, apkName=name, apkSize=apk.stat().st_size, sha256=digest)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = build_manifest(json.loads(args.release.read_text()), args.assets, args.aapt)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
