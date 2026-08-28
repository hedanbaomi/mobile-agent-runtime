// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
import { createHash } from "node:crypto";

export function rolloutBucket(announcementId, rolloutSalt, installId) {
  const compact = JSON.stringify([announcementId, rolloutSalt, installId]);
  const digest = createHash("sha256").update(compact).digest();
  let bucket = 0;
  for (let i = 0; i < 8; i += 1) {
    bucket = (bucket * 256 + digest[i]) % 100;
  }
  return bucket;
}
