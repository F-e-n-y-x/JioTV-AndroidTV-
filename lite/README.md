# jtv-lite

A tiny JTV server (under 1 MB) for routers and small boxes. It signs in to Jio once, keeps the
tokens fresh, and hands them to the JTV app on your TVs and phones. It does **not** proxy streams,
host playlists or the EPG; use the full `server/` for that.

Everything runs on one port, **29180**: the admin page, the app API, and `/jtv-server`, which the
app uses to find the server on your network. It also announces itself over mDNS
(`_jtv-server._tcp`).

## Quick start (any machine)

1. Download the binary for your machine from the release (`jtv-lite-linux-mipsle`, `-mips`, `-arm`,
   `-arm64`, `-amd64`, `jtv-lite-windows-amd64.exe`).
2. Run it: `chmod +x jtv-lite-linux-amd64 && ./jtv-lite-linux-amd64`
3. Open `http://<this-machine-ip>:29180`, choose an admin password, and sign in to Jio with your
   mobile number and the OTP.
4. In the JTV app, pick the server (or type the address shown on the page) and enter an access
   code. A code called "Default" is created at setup, and you can add one per TV.

Options: `-port` (env `PORT`, default 29180), `-name` (env `SERVER_NAME`, the name shown in the
app, default the hostname), `-data` (env `JTV_DATA`, default `./jtv-lite.json`). If you change
`-port`, the server still answers `/jtv-server` on 29180 so the app can find it.

On Linux and Windows builds, jtv-lite calls Jio through the system's `curl` (or OpenWrt's
`uclient-fetch`), so one of them must be installed. Windows 10 and later ship `curl.exe`.

## OpenWrt

**Needs:** OpenWrt 21.02 or newer, about 1.2 MB free flash, and an HTTPS fetch tool
(`uclient-fetch` with `libustream-mbedtls` and `ca-bundle` is on stock images). ARMv7, ARM64,
MIPS (24Kc/74Kc/1004Kc, either endian) and x86-64 work. ARMv5/v6 are not supported.

**One-line install** (run on the router over SSH):

```sh
wget -qO- https://raw.githubusercontent.com/F-e-n-y-x/JioTV-AndroidTV-/v2-lab/lite/openwrt/install.sh | sh
```

The installer picks the right binary, installs it to `/usr/bin/jtv-lite` along with the procd
service `/etc/init.d/jtv-lite` (starts at boot, restarts on crash, keeps data in `/etc/jtv-lite/`),
opens 29180/tcp and 5353/udp on the LAN zone, and prints the address to open.

**Manual install:**

```sh
scp -O jtv-lite-linux-mipsle root@192.168.1.1:/usr/bin/jtv-lite
scp -O openwrt/jtv-lite.init root@192.168.1.1:/etc/init.d/jtv-lite
ssh root@192.168.1.1 'chmod +x /usr/bin/jtv-lite /etc/init.d/jtv-lite && /etc/init.d/jtv-lite enable && /etc/init.d/jtv-lite start'
```

**Firewall:** the LAN zone usually accepts everything already. If it doesn't, run:

```sh
uci add firewall rule; uci set firewall.@rule[-1].name=Allow-jtv-lite; uci set firewall.@rule[-1].src=lan
uci set firewall.@rule[-1].proto=tcp; uci set firewall.@rule[-1].dest_port=29180; uci set firewall.@rule[-1].target=ACCEPT
uci commit firewall; /etc/init.d/firewall reload
```

**Update:** run the one-line install again. Your settings are kept.

**Uninstall:** `wget -qO- …/install.sh | sh -s uninstall`. This keeps `/etc/jtv-lite`; add
`--purge` to delete it too.

**Troubleshooting**

- Logs: `logread -e jtv-lite`
- `address already in use`: something else is on 29180. Start with `-port 8090` (edit the
  `command` line in `/etc/init.d/jtv-lite`).
- `mDNS off …`: `umdns` already owns 5353. That's fine, because the app also finds the server by
  scanning port 29180.
- No space in flash: run it from `/tmp` (RAM, gone after a reboot) or from USB, for example
  `/mnt/usb/jtv-lite -data /mnt/usb/jtv-lite.json`.
- `no curl or uclient-fetch found` or Jio calls fail: `opkg update && opkg install uclient-fetch libustream-mbedtls ca-bundle`
  (on OpenWrt 25 and later, use `apk add` with the same packages).

## Linux / Raspberry Pi (systemd)

```sh
sudo install -m755 jtv-lite-linux-arm64 /usr/local/bin/jtv-lite && sudo mkdir -p /var/lib/jtv-lite && printf '[Unit]\nDescription=jtv-lite\nAfter=network-online.target\n[Service]\nExecStart=/usr/local/bin/jtv-lite -data /var/lib/jtv-lite/jtv-lite.json\nRestart=always\n[Install]\nWantedBy=multi-user.target\n' | sudo tee /etc/systemd/system/jtv-lite.service && sudo systemctl enable --now jtv-lite
```

## Windows

Run `jtv-lite-windows-amd64.exe` and allow it through Windows Firewall on private networks
(29180/tcp). To start it at logon, put a shortcut to it in `shell:startup`.

## Docker

```sh
cd lite && docker compose up -d --build
```

The Docker build uses Go's own HTTPS client, so it needs no curl. Because Docker's bridge network
doesn't carry multicast to your LAN, mDNS won't reach the app. That's fine, because the app's
scan of port 29180 still finds the server. Use `network_mode: host` if you want mDNS too.

## Build

`./build.sh 0.1.0` cross-compiles every target into `dist/` (Go 1.24 or newer; it runs `upx` too
if it's installed). Run the tests with `go test ./...`.

## API (what the app uses)

- `GET /jtv-server` returns `{"app":"jtv-server","kind":"lite","name","port","https":false,"version"}`
- `GET /api/status` returns `{ok, hasCredentials}`
- `GET /api/credentials` with `Authorization: Bearer <code>` returns
  `{ssoToken, authToken, crmid, uniqueId, deviceId, userId}`. It returns 404 when the server isn't
  signed in to Jio, and 401 for a bad code.
- `POST /api/refresh` with the same header refreshes the token if it's close to expiry, then
  returns `{ok, error?, …credentials}`.
- `POST /api/reports` with the same header accepts a crash report (up to 256 KB). The newest 20
  are kept in memory and shown on the admin page.
