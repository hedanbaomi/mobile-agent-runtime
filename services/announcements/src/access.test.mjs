// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import assert from "node:assert/strict";
import { createSign, generateKeyPairSync } from "node:crypto";
import { AccessAuthenticator } from "./access.mjs";

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: "jwk" }), kid: "test-kid", alg: "RS256", use: "sig" };
const now = Math.floor(Date.now() / 1000);
const encode = (value) => Buffer.from(JSON.stringify(value)).toString("base64url");
const header = encode({ alg: "RS256", kid: "test-kid", typ: "JWT" });

function token(claims = {}) {
  const payload = encode({
    iss: "https://team.cloudflareaccess.com",
    aud: ["announcement-admin-aud"],
    sub: "test-subject",
    email: "admin@example.test",
    iat: now,
    exp: now + 300,
    ...claims,
  });
  const signature = createSign("RSA-SHA256").update(`${header}.${payload}`).sign(privateKey).toString("base64url");
  return `${header}.${payload}.${signature}`;
}

const authenticator = new AccessAuthenticator({
  teamDomain: "https://team.cloudflareaccess.com",
  audience: "announcement-admin-aud",
  clock: () => now * 1000,
  fetchImpl: async () => new Response(JSON.stringify({ keys: [jwk] }), { status: 200 }),
});
const result = await authenticator.authenticate(new Request("https://worker.example/admin/v1/stats", {
  headers: { "Cf-Access-Jwt-Assertion": token() },
}));
assert.equal(result.actor, "admin@example.test");
await assert.rejects(
  authenticator.authenticate(new Request("https://worker.example/admin/v1/stats", {
    headers: { "Cf-Access-Jwt-Assertion": token({ aud: ["wrong-aud"] }) },
  })),
  (error) => error.status === 401,
);
await assert.rejects(
  authenticator.authenticate(new Request("https://worker.example/admin/v1/stats", {
    headers: { "Cf-Access-Jwt-Assertion": token({ exp: now - 600 }) },
  })),
  (error) => error.status === 401,
);
console.log("Cloudflare Access JWT issuer/audience/time/signature checks ok");

