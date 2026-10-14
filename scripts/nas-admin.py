#!/usr/bin/env python3
"""Run an explicitly requested admin command on a configured SSH target."""
import argparse
import os
import shlex
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--host', default=os.environ.get('NAS_SSH_TARGET'), help='SSH host/alias, or NAS_SSH_TARGET')
parser.add_argument('--password-file', type=Path, default=Path(__file__).resolve().parent.parent / '.secrets/ssh-password')
parser.add_argument('command')
args = parser.parse_args()
if not args.host or args.host.startswith('-'):
    parser.error('Set --host or NAS_SSH_TARGET')
password = args.password_file.read_text()
command = "sudo -S -p '' sh -c " + shlex.quote(args.command)
raise SystemExit(subprocess.run(['ssh', '-o', 'BatchMode=yes', args.host, command], input=password, text=True).returncode)
