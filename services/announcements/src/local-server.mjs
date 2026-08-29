// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createWorker } from "./app.mjs";
import { generateKeyPair } from "./sign.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const adminPage = join(root, "admin", "announcements", "index.html");
const port = Number(process.env.PORT || 8787);
const keys = generateKeyPair();
const adminToken = process.env.MAR_ADMIN_TOKEN || "";
if (!adminToken) {
  console.error("Set MAR_ADMIN_TOKEN for local admin writes. Public feed still serves.");
}
const worker = createWorker({ keys, adminToken, environment: "local", allowLocalAdmin: true });

const server = createServer(async (req, res) => {
  try {
    const host = req.headers.host || `127.0.0.1:${port}`;
    const url = new URL(req.url || "/", `http://${host}`);
    if (url.pathname === "/admin/announcements" || url.pathname === "/admin/announcements/") {
      const html = await readFile(adminPage, "utf8");
      res.writeHead(200, {
        "content-type": "text/html; charset=utf-8",
        "x-content-type-options": "nosniff",
        "x-frame-options": "DENY",
        "referrer-policy": "no-referrer",
        "content-security-policy": "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src https: data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
      });
      res.end(html);
      return;
    }
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = Buffer.concat(chunks);
    const headers = new Headers();
    for (const [name, value] of Object.entries(req.headers)) {
      if (value) headers.set(name, Array.isArray(value) ? value.join(", ") : value);
    }
    const request = new Request(url, { method: req.method, headers, body: ["GET", "HEAD"].includes(req.method || "GET") ? undefined : body });
    const response = await worker.fetch(request);
    const outHeaders = {};
    response.headers.forEach((value, name) => {
      outHeaders[name] = value;
    });
    res.writeHead(response.status, outHeaders);
    res.end(Buffer.from(await response.arrayBuffer()));
  } catch (error) {
    res.writeHead(500, { "content-type": "text/plain" });
    res.end("local worker error");
    console.error(error);
  }
});

server.listen(port, "127.0.0.1", () => {
  console.log(`local announcement worker http://127.0.0.1:${port}`);
  console.log(`admin page http://127.0.0.1:${port}/admin/announcements`);
  console.log(`MAR_ANNOUNCE_KEY_ID=${keys.keyId}`);
  console.log(`MAR_ANNOUNCE_PUBLIC_KEY_HEX=${keys.publicKeyRaw.toString("hex")}`);
});
