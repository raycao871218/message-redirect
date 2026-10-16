#!/usr/bin/env python3
"""Generate credentials for a new deployment without replacing existing secret files."""
import argparse
import os
from pathlib import Path
import secrets

def initialize(directory):
    directory = Path(directory)
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    created = []
    for name in ["api-token", "db-password", "db-root-password"]:
        path = directory / name
        try:
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            continue
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(secrets.token_urlsafe(36))
        # Private parent (0700) protects host access; containers can read the bind-mounted file.
        path.chmod(0o444)
        created.append(name)
    return created

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", default=".secrets")
    args = parser.parse_args()
    print("Created:", ", ".join(initialize(args.directory)) or "none; existing credentials retained")
    print("Use the api-token file's content as the App access token. Keep the directory private.")
