#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p backend/wheels
python3 -m pip download --dest backend/wheels --platform manylinux2014_x86_64 --python-version 312 --implementation cp --abi cp312 --only-binary=:all: -r backend/requirements.txt
