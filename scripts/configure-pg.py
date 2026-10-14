"""Optional admin tool: back up HBA, allow an explicit database/user/client, reload PG."""
import argparse
import datetime
import ipaddress
import re
import shutil
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--hba', type=Path, default=Path('/etc/postgresql/pg_hba.conf'))
parser.add_argument('--database', required=True)
parser.add_argument('--user', required=True)
parser.add_argument('--client', required=True, help='Single container IPv4/IPv6 address')
parser.add_argument('--backup-dir', type=Path, required=True)
args = parser.parse_args()
for value in (args.database, args.user):
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', value):
        parser.error('Database and role must be simple identifiers')
try:
    address = ipaddress.ip_address(args.client)
except ValueError:
    parser.error('Client must be a single IP address')
rule = f'host    {args.database}    {args.user}    {address}/{address.max_prefixlen}    md5'
text = args.hba.read_text()
if rule not in text:
    args.backup_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    shutil.copy2(args.hba, args.backup_dir / ('pg_hba.before-sms-' + stamp + '.conf'))
    args.hba.write_text(text.rstrip() + '\n\n# Message Redirect: explicitly configured ingestion client\n' + rule + '\n')
    print('Added HBA rule; original configuration backed up.')
subprocess.run(['su', 'postgres', '-s', '/bin/sh', '-c', 'psql -d postgres -v ON_ERROR_STOP=1 -At'],
               input='SELECT pg_reload_conf();\n', text=True, check=True)
