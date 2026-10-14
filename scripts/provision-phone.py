"""Configure this app over authorized ADB without printing API credentials."""
import argparse
import os
import shutil
from urllib.parse import urlsplit
import json
import subprocess
import shlex
import sys
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('serial')
parser.add_argument('--server-url',required=True)
args=parser.parse_args()
config = json.loads((root / '.secrets/server.json').read_text())
serial=args.serial
address=urlsplit(args.server_url)
if address.scheme not in ('http','https') or not address.hostname or address.username or address.password or address.query or address.fragment:
    parser.error('Use an HTTP/HTTPS base URL without credentials, query or fragment')
if len(config.get('api_token',''))<32:parser.error('Configure an API token of at least 32 characters')
adb = os.environ.get('ADB') or shutil.which('adb')
if not adb:parser.error('Add adb to PATH or set ADB')
base = [adb, '-s', serial]
subprocess.run(base + ['shell', 'am', 'force-stop', 'com.nas.messageredirect'], check=True, capture_output=True)
old = subprocess.run(base + ['shell','run-as','com.nas.messageredirect','cat','shared_prefs/sync.xml'], capture_output=True, text=True)
device_id = str(uuid.uuid4())
if old.returncode == 0:
    try:
        for n in ET.fromstring(old.stdout):
            if n.get('name') == 'device_id' and n.text:
                device_id = n.text
    except ET.ParseError:
        pass
prefs = ET.Element('map')
for key,value in [('url',args.server_url.rstrip('/')),('token',config['api_token']),('device_id',device_id)]:
    ET.SubElement(prefs,'string',{'name':key}).text=value
ET.SubElement(prefs,'boolean',{'name':'enabled','value':'true'})
subprocess.run(base + ['shell','run-as','com.nas.messageredirect','mkdir','-p','shared_prefs'],check=True,capture_output=True)
subprocess.run(base + ['shell','run-as','com.nas.messageredirect','sh','-c',shlex.quote('cat > shared_prefs/sync.xml')],input=ET.tostring(prefs, encoding='utf-8', xml_declaration=True),check=True,capture_output=True)
subprocess.run(base + ['shell','am','start','-n','com.nas.messageredirect/.MainActivity'],check=True,capture_output=True)
print('Phone configured; auto upload enabled. Device details remain in .secrets.')
(root / '.secrets' / ('device-' + serial + '.json')).write_text(json.dumps({'device_id':device_id}))
