// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
import { rolloutBucket } from "./rollout.mjs";

export default {
  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/api/v1/announcements" && request.method === "GET") {
      return Response.json({
        schemaVersion: 1,
        complete: true,
        items: [],
        withdrawn: [],
        rolloutDemo: rolloutBucket("security-demo", "stable-salt", "00000000-0000-4000-8000-000000000002"),
      });
    }
    if (url.pathname === "/api/v1/events" && request.method === "POST") {
      return new Response(null, { status: 204 });
    }
    return new Response("not found", { status: 404 });
  },
};
