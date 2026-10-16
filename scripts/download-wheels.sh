#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p server/wheels
arch="${WHEEL_ARCH:-x86_64}"
version="${PYTHON_VERSION:-312}"
case "$arch" in x86_64|aarch64) ;; *) echo 'WHEEL_ARCH must be x86_64 or aarch64' >&2; exit 1 ;; esac
python3 -m pip download --dest server/wheels \
  --platform "manylinux_2_34_$arch" --platform "manylinux_2_28_$arch" --platform "manylinux2014_$arch" \
  --python-version "$version" --implementation cp --abi "cp$version" --only-binary=:all: -r server/requirements.txt
