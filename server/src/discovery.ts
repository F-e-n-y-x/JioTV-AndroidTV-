import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import os from "node:os";
import { config } from "./config";

/**
 * LAN discovery: the app checks every address on its home network for this port and lists the JTV
 * servers that answer (the light router server answers the same way). Plain HTTP on a fixed port so
 * it works with ordinary Docker port mapping, unlike multicast.
 */
/** The `/jtv-server` answer: also served on the main port, so the app can check a typed address. */
export function discoveryInfo(): string {
  return JSON.stringify({
    app: "jtv-server",
    kind: "full",
    name: config.serverName || os.hostname(),
    port: config.publicPort,
    https: false,
    version: serverVersion,
  });
}

const serverVersion = (() => {
  try { return JSON.parse(fs.readFileSync(path.resolve(__dirname, "..", "package.json"), "utf8")).version as string; }
  catch { return ""; }
})();

export function startDiscovery(): http.Server | null {
  // One port does everything by default: the main listener already answers /jtv-server.
  if (!config.discoveryPort || config.discoveryPort === config.port) return null;
  const body = discoveryInfo();
  const server = http.createServer((req, res) => {
    if (req.method === "GET" && req.url?.split("?")[0] === "/jtv-server") {
      res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
      res.end(body);
    } else {
      res.writeHead(404).end();
    }
  });
  server.on("error", (err) => console.warn("Discovery port not started:", err.message));
  server.listen(config.discoveryPort, config.host, () =>
    console.log(`JTV discovery      → :${config.discoveryPort}/jtv-server`),
  );
  return server;
}
