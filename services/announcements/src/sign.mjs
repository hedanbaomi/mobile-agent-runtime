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
