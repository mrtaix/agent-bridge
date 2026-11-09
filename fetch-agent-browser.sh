#!/bin/sh
# Tải binary agent-browser (Apache-2.0) từ npm rồi đặt vào assets/.
set -eu
cd "$(dirname "$0")"
VERSION=${AGENT_BROWSER_VERSION:-0.41.2}
OUT=assets/agent-browser

if [ -s "$OUT" ]; then
    echo "assets/agent-browser đã có ($(wc -c < "$OUT") bytes)"
    exit 0
fi

mkdir -p assets build
URL="https://registry.npmjs.org/agent-browser/-/agent-browser-$VERSION.tgz"
echo "tải $URL"
curl -fsSL "$URL" -o build/agent-browser.tgz
tar -xzf build/agent-browser.tgz -C build package/bin/agent-browser-linux-musl-arm64
cp build/package/bin/agent-browser-linux-musl-arm64 "$OUT"
chmod 644 "$OUT"
rm -rf build/package build/agent-browser.tgz
echo "xong: $OUT ($(wc -c < "$OUT") bytes)"
