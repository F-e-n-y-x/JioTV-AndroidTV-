#!/bin/sh
# Cross-compiles jtv-lite into dist/. Usage: ./build.sh [version]
set -eu
cd "$(dirname "$0")"
VER=${1:-$(git describe --tags --always 2>/dev/null || echo dev)}
mkdir -p dist
build() { # goos goarch out [env...]
  os=$1 arch=$2 out=dist/$3; shift 3
  env CGO_ENABLED=0 GOOS=$os GOARCH=$arch "$@" go build -trimpath -ldflags "-s -w -X main.version=$VER" -o "$out" .
  raw=$(wc -c < "$out")
  if command -v upx >/dev/null 2>&1; then upx -q --best --lzma "$out" >/dev/null; fi
  printf '%-32s raw %8d  final %8d\n' "$out" "$raw" "$(wc -c < "$out")"
}
build linux mipsle jtv-lite-linux-mipsle GOMIPS=softfloat
build linux mips jtv-lite-linux-mips GOMIPS=softfloat
build linux arm jtv-lite-linux-arm GOARM=7
build linux arm64 jtv-lite-linux-arm64
build linux amd64 jtv-lite-linux-amd64
build windows amd64 jtv-lite-windows-amd64.exe
