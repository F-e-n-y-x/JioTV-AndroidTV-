#!/bin/sh
# jtv-lite installer for OpenWrt (busybox ash).
#   install:   wget -qO- https://raw.githubusercontent.com/F-e-n-y-x/JioTV-AndroidTV-/v2-lab/lite/openwrt/install.sh | sh
#   uninstall: wget -qO- .../install.sh | sh -s uninstall [--purge]   (--purge also deletes /etc/jtv-lite)
set -e

VERSION=0.1.0
REPO=F-e-n-y-x/JioTV-AndroidTV-
BIN_URL="https://github.com/$REPO/releases/download/lite-v$VERSION"
INIT_URL="https://raw.githubusercontent.com/$REPO/v2-lab/lite/openwrt/jtv-lite.init"
PORT=29180

die() { echo "jtv-lite: $*" >&2; exit 1; }

fix_https() {
	if command -v apk >/dev/null 2>&1; then
		echo "  apk update && apk add ca-bundle libustream-mbedtls uclient-fetch" >&2
	else
		echo "  opkg update && opkg install ca-bundle libustream-mbedtls uclient-fetch" >&2
	fi
}

# download URL FILE
download() {
	if command -v curl >/dev/null 2>&1; then
		curl -fsSL -o "$2" "$1"
	elif command -v uclient-fetch >/dev/null 2>&1; then
		uclient-fetch -q -O "$2" "$1"
	else
		wget -q -O "$2" "$1"
	fi || {
		rm -f "$2"
		echo "jtv-lite: download failed: $1" >&2
		echo "No HTTPS download tool / CA certificates? Fix with:" >&2
		fix_https
		exit 1
	}
}

detect_arch() {
	a=$( (apk --print-arch || opkg print-architecture | awk '$2!="all" && $2!="noarch" {x=$2} END {print x}') 2>/dev/null)
	[ -n "$a" ] || a=$(uname -m)
	case "$a" in
	mipsel* | mipsle*) echo mipsle ;;
	mips64*) die "64-bit MIPS ($a) is not built yet" ;;
	mips*)
		# uname -m says just "mips" for both endians; ask the CPU.
		[ "$(printf '\1\0' | od -An -tu2 | tr -d ' ')" = 1 ] && echo mipsle || echo mips ;;
	aarch64* | arm64*) echo arm64 ;;
	arm*)
		case "$a" in arm_arm926* | arm_arm1176* | arm_fa526* | arm_xscale* | armv5* | armv6*)
			die "this ARM CPU ($a) is older than ARMv7 and is not supported" ;;
		esac
		echo arm ;;
	x86_64 | amd64) echo amd64 ;;
	*) die "unsupported CPU: $a" ;;
	esac
}

fw_rule() { # section proto port name
	uci -q get "firewall.$1" >/dev/null && return 0
	uci set "firewall.$1=rule"
	uci set "firewall.$1.name=$4"
	uci set "firewall.$1.src=lan"
	uci set "firewall.$1.proto=$2"
	uci set "firewall.$1.dest_port=$3"
	uci set "firewall.$1.target=ACCEPT"
	FW_CHANGED=1
}

uninstall() {
	[ -x /etc/init.d/jtv-lite ] && { /etc/init.d/jtv-lite stop || true; /etc/init.d/jtv-lite disable || true; }
	rm -f /etc/init.d/jtv-lite /usr/bin/jtv-lite
	if command -v uci >/dev/null 2>&1; then
		uci -q delete firewall.jtv_lite || true
		uci -q delete firewall.jtv_lite_mdns || true
		uci commit firewall
		/etc/init.d/firewall reload >/dev/null 2>&1 || true
	fi
	if [ "$1" = "--purge" ]; then rm -rf /etc/jtv-lite; echo "jtv-lite removed (data deleted)."
	else echo "jtv-lite removed. Your settings are kept in /etc/jtv-lite (use --purge to delete them)."; fi
}

[ "$1" = uninstall ] && { uninstall "$2"; exit 0; }
[ "$(id -u)" = 0 ] || die "run as root"

ARCH=$(detect_arch)
free=$(df -k /usr/bin | awk 'NR==2 {print $4}')
[ "${free:-0}" -ge 1200 ] || die "only ${free} KB free in /usr/bin, need ~1.2 MB. Run it from /tmp or USB instead (see README: manual install)."

echo "Installing jtv-lite $VERSION ($ARCH)..."
download "$BIN_URL/jtv-lite-linux-$ARCH" /tmp/jtv-lite.new
download "$INIT_URL" /tmp/jtv-lite.init.new
[ -x /etc/init.d/jtv-lite ] && /etc/init.d/jtv-lite stop >/dev/null 2>&1 || true
mv /tmp/jtv-lite.new /usr/bin/jtv-lite
mv /tmp/jtv-lite.init.new /etc/init.d/jtv-lite
chmod 755 /usr/bin/jtv-lite /etc/init.d/jtv-lite
mkdir -p /etc/jtv-lite && chmod 700 /etc/jtv-lite

if ! command -v curl >/dev/null 2>&1 && ! command -v uclient-fetch >/dev/null 2>&1; then
	echo "Warning: jtv-lite needs curl or uclient-fetch (with HTTPS) to talk to Jio. Install with:"
	fix_https
fi

if command -v uci >/dev/null 2>&1; then
	FW_CHANGED=0
	fw_rule jtv_lite tcp $PORT Allow-jtv-lite
	fw_rule jtv_lite_mdns udp 5353 Allow-jtv-lite-mDNS
	if [ "$FW_CHANGED" = 1 ]; then uci commit firewall; /etc/init.d/firewall reload >/dev/null 2>&1 || true; fi
fi

/etc/init.d/jtv-lite enable
/etc/init.d/jtv-lite start

ip=$(uci -q get network.lan.ipaddr 2>/dev/null | cut -d/ -f1)
echo "Done. Open http://${ip:-<router-ip>}:$PORT to set a password and sign in to Jio."
