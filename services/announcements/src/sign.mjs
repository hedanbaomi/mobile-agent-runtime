// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign as nodeSign } from "node:crypto";

export const SIGN_PREFIX = "MAR-ANNOUNCEMENTS-V1\n";
export const TEST_ONLY_SEED = Buffer.from(
  "0000000000000000000000000000000000000000000000000000000000000001",
  "hex",
);

export function generateKeyPair() {
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  return wrap(publicKey, privateKey, "local-dev-1");
}

export function keyPairFromSeed(seed = TEST_ONLY_SEED) {
  if (seed.length !== 32) {
    throw new Error("ed25519 seed must be 32 bytes");
  }
  const pkcs8 = Buffer.concat([Buffer.from("302e020100300506032b657004220420", "hex"), seed]);
  const privateKey = createPrivateKey({ key: pkcs8, format: "der", type: "pkcs8" });
  const publicKey = createPublicKey(privateKey);
  return wrap(publicKey, privateKey, "test-only-1");
}

/**
 * Import the production Ed25519 private key from a PKCS#8 DER secret. The
 * base64 value belongs in a Worker secret (`MAR_ANNOUNCE_PRIVATE_KEY_PKCS8`)
 * and is never written to a response, log, or D1 row.
 */
export function keyPairFromPkcs8(base64, keyId) {
  if (typeof base64 !== "string" || !base64 || !/^[A-Za-z0-9+/]+={0,2}$/.test(base64)) {
    throw new Error("MAR_ANNOUNCE_PRIVATE_KEY_PKCS8 must be base64 PKCS#8 DER");
  }
  const der = Buffer.from(base64, "base64");
  if (!der.length || der.toString("base64") !== base64.replace(/\s+/g, "")) {
    throw new Error("MAR_ANNOUNCE_PRIVATE_KEY_PKCS8 is not canonical base64");
  }
  const privateKey = createPrivateKey({ key: der, format: "der", type: "pkcs8" });
  const publicKey = createPublicKey(privateKey);
  const normalizedKeyId = typeof keyId === "string" ? keyId.trim() : "";
  if (!normalizedKeyId || normalizedKeyId.length > 128 || !/^[A-Za-z0-9._-]+$/.test(normalizedKeyId)) {
    throw new Error("MAR_ANNOUNCE_KEY_ID is invalid");
  }
  return wrap(publicKey, privateKey, normalizedKeyId);
}

function wrap(publicKey, privateKey, keyId) {
  return {
    publicKey,
    privateKey,
    publicKeyRaw: publicKey.export({ type: "spki", format: "der" }).subarray(-32),
    keyId,
  };
}

export function signPayload(privateKey, payloadBase64) {
  const message = Buffer.from(SIGN_PREFIX + payloadBase64, "utf8");
  return nodeSign(null, message, privateKey).toString("base64");
}

export function audienceHash(installId) {
  return createHash("sha256").update(installId, "utf8").digest("hex");
}

export function etagFor(feedVersion, installId, platform, channel, versionCode, locale) {
  const digest = createHash("sha256")
    .update(`${feedVersion}:${installId}:${platform}:${channel}:${versionCode}:${locale}`, "utf8")
    .digest("hex")
    .slice(0, 16);
  return `"${feedVersion}-${digest}"`;
}

export function sha256Hex(text) {
  return createHash("sha256").update(text, "utf8").digest("hex");
}
