// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
import assert from "node:assert/strict";
import { rolloutBucket } from "./rollout.mjs";

assert.equal(rolloutBucket("security-demo", "stable-salt", "00000000-0000-4000-8000-000000000001"), 44);
assert.equal(rolloutBucket("security-demo", "stable-salt", "00000000-0000-4000-8000-000000000002"), 27);
console.log("rollout golden vectors ok");
