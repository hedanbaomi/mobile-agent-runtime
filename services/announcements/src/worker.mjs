// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
import { createWorker } from "./app.mjs";
import { generateKeyPair } from "./sign.mjs";
import { MemoryStore } from "./store.mjs";

const store = new MemoryStore();
let worker;

function getWorker(env = {}) {
  if (!worker) {
    const keys = generateKeyPair();
    keys.keyId = env.MAR_ANNOUNCE_KEY_ID || keys.keyId;
    worker = createWorker({
      store,
      keys,
      adminToken: env.MAR_ADMIN_TOKEN || "",
    });
  }
  return worker;
}

export default {
  async fetch(request, env = {}) {
    return getWorker(env).fetch(request);
  },
};
