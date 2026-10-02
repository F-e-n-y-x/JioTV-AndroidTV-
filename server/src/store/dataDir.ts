import fs from "node:fs";
import path from "node:path";
import { config } from "../config";

/**
 * Makes sure DATA_DIR exists and that this process can read + write it and every file already in it.
 * Since the Docker image runs as the unprivileged `node` user (uid 1000), a volume created by an older
 * image (which ran as root) holds root-owned files: fail fast with the fix instead of crashing later
 * with an obscure SQLite "readonly database" or EACCES on config.json.
 */
export function ensureDataDirWritable(): void {
  const dir = config.dataDir;
  const bad: string[] = [];
  try {
    fs.mkdirSync(dir, { recursive: true });
    fs.accessSync(dir, fs.constants.R_OK | fs.constants.W_OK | fs.constants.X_OK);
  } catch {
    bad.push(dir);
  }
  if (!bad.length) {
    for (const name of fs.readdirSync(dir)) {
      const p = path.join(dir, name);
      try {
        fs.accessSync(p, fs.constants.R_OK | fs.constants.W_OK);
      } catch {
        bad.push(p);
      }
    }
  }
  if (!bad.length) return;
  const uid = typeof process.getuid === "function" ? process.getuid() : "?";
  const gid = typeof process.getgid === "function" ? process.getgid() : "?";
  console.error(
    `\n[data] The server (uid ${uid}) cannot read/write:\n  ${bad.join("\n  ")}\n` +
      `This happens after upgrading from an image that ran as root. Fix it once, then restart:\n` +
      `  docker run --rm -v <your-volume>:/app/data alpine chown -R ${uid}:${gid} /app/data\n` +
      `(see server/README.md, "Upgrading: data volume permissions").\n`
  );
  process.exit(1);
}
