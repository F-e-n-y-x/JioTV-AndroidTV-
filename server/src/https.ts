import fs from "node:fs";
import path from "node:path";
import selfsigned from "selfsigned";
import { config } from "./config";

/**
 * Built-in HTTPS via a self-signed certificate so the browser gets a "secure context" — required for
 * Widevine/EME playback anywhere other than localhost. The cert is generated once into DATA_DIR.
 * Browsers show a one-time "not private" warning for self-signed certs; after you proceed, EME works.
 */

const keyPath = path.join(config.dataDir, "tls-key.pem");
const certPath = path.join(config.dataDir, "tls-cert.pem");

export function hasCert(): boolean {
  return fs.existsSync(keyPath) && fs.existsSync(certPath);
}

export function generateCert(): void {
  const pems = selfsigned.generate([{ name: "commonName", value: "jtv-server" }], {
    days: 3650,
    keySize: 2048,
    algorithm: "sha256",
    extensions: [
      {
        name: "subjectAltName",
        altNames: [
          { type: 2, value: "localhost" },
          { type: 7, ip: "127.0.0.1" },
        ],
      },
    ],
  });
  fs.mkdirSync(config.dataDir, { recursive: true });
  fs.writeFileSync(keyPath, pems.private, { mode: 0o600 }); // private key: owner-only
  try { fs.chmodSync(keyPath, 0o600); } catch { /* existing file on odd FS */ }
  fs.writeFileSync(certPath, pems.cert);
}

export function ensureCert(): { key: Buffer; cert: Buffer } {
  if (!hasCert()) generateCert();
  return { key: fs.readFileSync(keyPath), cert: fs.readFileSync(certPath) };
}
