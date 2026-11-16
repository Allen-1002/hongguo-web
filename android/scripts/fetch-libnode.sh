#!/usr/bin/env bash
# 下载 nodejs-mobile 预编译的 Android 版 Node（libnode.so + 头文件）到 android/app/libnode/
set -euo pipefail
VERSION="${NODEJS_MOBILE_VERSION:-18.20.4}"
DIR="$(cd "$(dirname "$0")/.." && pwd)/app/libnode"
if [ -f "$DIR/bin/arm64-v8a/libnode.so" ]; then
  echo "libnode 已存在：$DIR"
  exit 0
fi
TMP="$(mktemp -d)"
curl -fsSL -o "$TMP/libnode.zip" \
  "https://github.com/nodejs-mobile/nodejs-mobile/releases/download/v${VERSION}/nodejs-mobile-v${VERSION}-android.zip"
mkdir -p "$DIR"
unzip -q "$TMP/libnode.zip" -d "$DIR"
rm -rf "$TMP"
ls -la "$DIR/bin"
