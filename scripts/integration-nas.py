import shlex
import subprocess
import sys
from pathlib import Path
root=Path(__file__).resolve().parent.parent
code=(root/'backend/tests/live_nas.py').read_text()
command='/usr/local/bin/docker exec message-redirect python -c '+shlex.quote(code)
raise SystemExit(subprocess.run([sys.executable,str(root/'scripts/nas-admin.py'),command]).returncode)
