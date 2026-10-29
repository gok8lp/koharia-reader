#!/usr/bin/env bash
# Flugel: review and run on flugelserver only after approving the server changes.
set -euo pipefail

if [[ $# != 1 ]]; then
  echo "Usage: bash tools/flugel/setup-server.sh /path/to/dedicated-deploy-key.pub" >&2
  exit 2
fi
if [[ $(id -un) != flugel || "$HOME" != /home/flugel ]]; then
  echo "Run this as flugel on flugelserver." >&2
  exit 1
fi
command -v python3 >/dev/null
command -v tailscale >/dev/null
script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# Refuse to replace any existing service on this port; preserve Komga on 10443.
tailscale serve status --json | python3 -c '
import json, sys
state = json.load(sys.stdin)
if "10444" in state.get("TCP", {}):
    sys.exit("Port 10444 already has a Serve configuration; review it before continuing.")
'
python3 - "$1" "$script_directory/release.py" <<'PY'
from pathlib import Path
import re
import shutil
import sys

public_key = Path(sys.argv[1]).read_text(encoding="utf-8").strip().split()
if len(public_key) < 2 or public_key[0] != "ssh-ed25519" or not re.fullmatch(r"[A-Za-z0-9+/]+=*", public_key[1]):
    sys.exit("Expected a dedicated Ed25519 deployment public key")
root = Path("/home/flugel/flugel-reader-updates")
publisher_directory = Path("/home/flugel/.local/lib/flugel-reader")
ssh_directory = Path("/home/flugel/.ssh")
authorized_keys = ssh_directory / "authorized_keys"
command = (
    '/usr/bin/python3 /home/flugel/.local/lib/flugel-reader/release.py publish '
    '--root /home/flugel/flugel-reader-updates '
    '--base-url https://flugelserver.tail42f1f4.ts.net:10444 --ssh'
)
entry = f'restrict,command="{command}" ssh-ed25519 {public_key[1]} flugel-reader-deploy'
existing = authorized_keys.read_text(encoding="utf-8") if authorized_keys.exists() else ""
if public_key[1] in existing and entry not in existing.splitlines():
    sys.exit("This key already has a different authorized_keys entry; review it manually")
root.mkdir(parents=True, exist_ok=True, mode=0o755)
root.chmod(0o755)
publisher_directory.mkdir(parents=True, exist_ok=True, mode=0o700)
publisher_directory.chmod(0o700)
shutil.copyfile(sys.argv[2], publisher_directory / "release.py")
(publisher_directory / "release.py").chmod(0o600)
ssh_directory.mkdir(exist_ok=True, mode=0o700)
ssh_directory.chmod(0o700)
if entry not in existing.splitlines():
    with authorized_keys.open("a", encoding="utf-8") as destination:
        if existing and not existing.endswith("\n"):
            destination.write("\n")
        destination.write(entry + "\n")
authorized_keys.chmod(0o600)
print("Installed the restricted publisher and deployment public key.")
PY
# This command may require the server's configured Tailscale operator privileges.
tailscale serve --bg --https=10444 /home/flugel/flugel-reader-updates
tailscale serve status
