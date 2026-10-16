#!/usr/bin/env python3
"""Build and test all database engines in isolated, temporary Docker resources."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--engines", nargs="+", default=["sqlite", "postgresql", "mysql", "mariadb"])
args = parser.parse_args()
run_id = "mr-test-" + uuid.uuid4().hex[:12]
image = run_id + ":local"
created_containers, created_networks, created_volumes = [], [], []
report = []
secret_directory = tempfile.TemporaryDirectory(prefix="message-redirect-test-")
token_file = Path(secret_directory.name) / "api-token"

def docker(*command, data=None, capture=False, timeout=300, check=True):
    result = subprocess.run(["docker", *command], input=data, text=True, stdout=subprocess.PIPE if capture else None, stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError("Docker operation failed: " + command[0])
    return result.stdout or ""

def ready(name):
    for _ in range(90):
        state = docker("inspect", "--format", "{{.State.Health.Status}}", name, capture=True, timeout=15).strip()
        if state == "healthy":
            return
        if state == "unhealthy":
            raise RuntimeError("Test container did not become healthy")
        time.sleep(2)
    raise RuntimeError("Timed out starting test container")

try:
    docker("build", "-t", image, str(root / "server"), timeout=900)
    for engine in args.engines:
        if engine not in {"sqlite", "postgresql", "mysql", "mariadb"}:
            raise ValueError("Unsupported test engine")
        print("Testing isolated engine:", engine, flush=True)
        network, api, db = run_id + "-" + engine, run_id + "-" + engine + "-api", run_id + "-" + engine + "-db"
        docker("network", "create", network, capture=True); created_networks.append(network)
        password, token, device_id = secrets.token_hex(24), secrets.token_urlsafe(32), str(uuid.uuid4())
        if token_file.exists():
            token_file.chmod(0o600)
        token_file.write_text(token, encoding="utf-8"); token_file.chmod(0o444)
        environment = {"SMS_API_TOKEN_FILE": "/run/secrets/api-token", "SMS_DB_TYPE": engine, "SMS_TEST_DEVICE_ID": device_id}
        mounts = []
        if engine == "sqlite":
            volume = network + "-data"; docker("volume", "create", volume, capture=True); created_volumes.append(volume)
            mounts = ["-v", volume + ":/data"]
        else:
            db_image = {"postgresql": "postgres:17-alpine", "mysql": "mysql:8.4", "mariadb": "mariadb:11.4"}[engine]
            docker("pull", db_image, timeout=600)
            prefix = {"postgresql": "POSTGRES", "mysql": "MYSQL", "mariadb": "MARIADB"}[engine]
            env = {prefix + ("_DB" if engine == "postgresql" else "_DATABASE"): "sms_archive", prefix + "_USER": "sms_ingest", prefix + "_PASSWORD": password}
            if engine != "postgresql":
                env[prefix + "_ROOT_PASSWORD"] = secrets.token_hex(24)
            health = "pg_isready -U sms_ingest -d sms_archive" if engine == "postgresql" else "healthcheck.sh --connect --innodb_initialized" if engine == "mariadb" else "mysqladmin ping -h 127.0.0.1 --silent"
            command = ["run", "-d", "--name", db, "--network", network, "--network-alias", "database", "--memory", "512m", "--health-cmd", health, "--health-interval", "2s", "--health-retries", "60"]
            db_volume = network + "-db-data"; docker("volume", "create", db_volume, capture=True); created_volumes.append(db_volume)
            command.extend(["-v", db_volume + (":/var/lib/postgresql/data" if engine == "postgresql" else ":/var/lib/mysql")])
            for key, value in env.items():
                command.extend(["-e", key + "=" + value])
            created_containers.append(db); docker(*command, db_image, capture=True); ready(db)
            environment.update(SMS_DB_HOST="database", SMS_DB_PORT="5432" if engine == "postgresql" else "3306", SMS_DB_NAME="sms_archive", SMS_DB_USER="sms_ingest", SMS_DB_PASSWORD=password)
        command = ["run", "-d", "--name", api, "--network", network, "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true", "--tmpfs", "/tmp:size=16m", "--memory", "256m", "--health-interval", "2s", "--health-start-period", "2s", "--mount", "type=bind,src=" + str(token_file.resolve()) + ",dst=/run/secrets/api-token,readonly", *mounts]
        for key, value in environment.items():
            command.extend(["-e", key + "=" + value])
        created_containers.append(api); docker(*command, image, capture=True); ready(api)
        smoke = (root / "server/tests/container_smoke.py").read_text(encoding="utf-8")
        docker("exec", "-i", api, "python", "-", data=smoke)
        docker("rm", "-f", "-v", api, capture=True)
        docker(*command, image, capture=True); ready(api)
        docker("exec", "-i", "-e", "SMS_TEST_PHASE=verify", api, "python", "-", data=smoke)
        report.append({"engine": engine, "status": "passed", "container_replacement": True})
        docker("rm", "-f", "-v", api, capture=True)
        if engine != "sqlite":
            docker("rm", "-f", "-v", db, capture=True)
    print(json.dumps({"results": report}, indent=2), flush=True)
finally:
    # Names are generated for this run only. Never prune or touch unrelated resources.
    for name in reversed(created_containers):
        docker("rm", "-f", "-v", name, capture=True, check=False)
    for name in reversed(created_volumes):
        docker("volume", "rm", name, capture=True, check=False)
    for name in reversed(created_networks):
        docker("network", "rm", name, capture=True, check=False)
    docker("image", "rm", image, capture=True, check=False)
    if token_file.exists():
        token_file.chmod(0o600)
    secret_directory.cleanup()
