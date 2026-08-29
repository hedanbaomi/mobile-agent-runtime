// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { createAccessAuthenticator } from "./access.mjs";
import { createUnavailableWorker, createWorker } from "./app.mjs";
import { D1Store } from "./d1-store.mjs";
import { generateKeyPair, keyPairFromPkcs8 } from "./sign.mjs";

let cached;
let cachedBinding;
let cachedEnvironment;

function configuredEnvironment(env) {
  const raw = env.MAR_ENV;
  if (raw == null || String(raw).trim() === "") return "production";
  const value = String(raw).trim().toLowerCase();
  if (value === "production") return "production";
  if (value === "local") return "local";
  throw new Error("MAR_ENV must be production or local");
}

function getWorker(env = {}) {
  // A Worker isolate keeps this instance for many requests. Recreate it when
  // the local runtime swaps its D1 binding during a reload.
  let environment;
  try {
    environment = configuredEnvironment(env);
    const production = environment === "production";
    if (cached && cachedBinding === env.ANNOUNCEMENTS_DB && cachedEnvironment === environment) return cached;
    if (!env.ANNOUNCEMENTS_DB) throw new Error("ANNOUNCEMENTS_DB binding is required");
    const keyId = String(env.MAR_ANNOUNCE_KEY_ID || "").trim();
    let keys;
    if (env.MAR_ANNOUNCE_PRIVATE_KEY_PKCS8) {
      keys = keyPairFromPkcs8(env.MAR_ANNOUNCE_PRIVATE_KEY_PKCS8, keyId);
    } else if (!production) {
      keys = generateKeyPair();
      if (keyId) keys.keyId = keyId;
    } else {
      throw new Error("MAR_ANNOUNCE_PRIVATE_KEY_PKCS8 is required");
    }
    if (!keyId && production) throw new Error("MAR_ANNOUNCE_KEY_ID is required");
    const store = new D1Store(env.ANNOUNCEMENTS_DB, { keyId: keys.keyId });
    const accessAuthenticator = createAccessAuthenticator(env);
    cached = createWorker({
      store,
      keys,
      // This value is intentionally ignored in production by createWorker.
      // Keeping it out of the production configuration prevents token auth
      // from becoming an accidental Access fallback.
      adminToken: production ? "" : (env.MAR_ADMIN_TOKEN || ""),
      environment: production ? "production" : "local",
      allowLocalAdmin: !production,
      accessAuthenticator,
      adminOrigins: env.MAR_ADMIN_ORIGIN ? [env.MAR_ADMIN_ORIGIN] : [],
      publicOrigins: env.MAR_PUBLIC_ORIGIN ? [env.MAR_PUBLIC_ORIGIN] : [],
      assets: env.ASSETS,
    });
    cachedBinding = env.ANNOUNCEMENTS_DB;
    cachedEnvironment = environment;
  } catch {
    // Never start a partially configured public service. The generic response
    // intentionally does not disclose which binding or secret is missing.
    cached = createUnavailableWorker();
    cachedBinding = env.ANNOUNCEMENTS_DB;
    cachedEnvironment = environment || "invalid";
  }
  return cached;
}

export default {
  async fetch(request, env = {}) {
    return getWorker(env).fetch(request);
  },
  async scheduled(event, env = {}) {
    return getWorker(env).scheduled(event);
  },
};
