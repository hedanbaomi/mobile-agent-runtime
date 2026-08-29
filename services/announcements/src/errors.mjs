// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

export class HttpError extends Error {
  constructor(status, message, extra = {}) {
    super(message);
    this.name = "HttpError";
    this.status = status;
    this.extra = extra;
  }
}
