// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { HttpError } from "./errors.mjs";

const ACCESS_HEADER = "Cf-Access-Jwt-Assertion";
const DEFAULT_SKEW_SECONDS = 60;
const DEFAULT_CACHE_MS = 5 * 60 * 1000;

/**
 * Minimal Cloudflare Access JWT verifier for the Worker admin API.
 *
 * Access places the assertion in Cf-Access-Jwt-Assertion after the request
 * passes the Access application policy. The Worker still verifies the
 * signature, issuer, audience and time claims so an assertion cannot be
 * replayed against a different deployment.
 */
export class AccessAuthenticator {
  constructor(options = {}) {
    this.teamDomain = normalizeTeamDomain(options.teamDomain);
    this.audience = String(options.audience || "").trim();
    this.fetchImpl = options.fetchImpl || globalThis.fetch;
    this.clock = options.clock || (() => Date.now());
    this.skewSeconds = Number.isFinite(options.skewSeconds) ? options.skewSeconds : DEFAULT_SKEW_SECONDS;
    this.cacheMs = Number.isFinite(options.cacheMs) ? options.cacheMs : DEFAULT_CACHE_MS;
    this.certificates = null;
  }

  configured() {
    return Boolean(this.teamDomain && this.audience && typeof this.fetchImpl === "function");
  }

  async authenticate(request) {
    if (!this.configured()) throw new HttpError(503, "admin disabled until Cloudflare Access is configured");
    const token = request.headers.get(ACCESS_HEADER) || "";
    if (!token || token.length > 32 * 1024) throw new HttpError(401, "admin authentication required");
    const parts = token.split(".");
    if (parts.length !== 3) throw new HttpError(401, "admin authentication failed");
    const header = decodeObject(parts[0]);
    const claims = decodeObject(parts[1]);
    if (header.alg !== "RS256" || typeof header.kid !== "string" || !header.kid) {
      throw new HttpError(401, "admin authentication failed");
    }
    validateClaims(claims, this.teamDomain, this.audience, this.clock(), this.skewSeconds);
    const key = await this.keyFor(header.kid);
    const verified = await crypto.subtle.verify(
      { name: "RSASSA-PKCS1-v1_5" },
      key,
      base64UrlToBytes(parts[2]),
      new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
    );
    if (!verified) throw new HttpError(401, "admin authentication failed");
    const actor = String(claims.email || claims.sub || "access-user").trim();
    if (!actor || actor.length > 256 || /[\u0000-\u001f\u007f]/.test(actor)) {
      throw new HttpError(401, "admin authentication failed");
    }
    return { actor, subject: claims.sub ? String(claims.sub) : null, claims };
  }

  async keyFor(kid) {
    const now = this.clock();
    if (!this.certificates || now - this.certificates.loadedAt > this.cacheMs) {
      this.certificates = await this.loadCertificates(now);
    }
    const jwk = this.certificates.keys.find((item) => item.kid === kid);
    if (!jwk) {
      // Access rotates certificates. Refresh once when a token references a
      // newly published kid, then fail closed if it is still unknown.
      this.certificates = await this.loadCertificates(now, true);
    }
    const refreshed = this.certificates.keys.find((item) => item.kid === kid);
    if (!refreshed || refreshed.kty !== "RSA" || refreshed.alg !== "RS256") {
      throw new HttpError(401, "admin authentication failed");
    }
    try {
      return await crypto.subtle.importKey(
        "jwk",
        refreshed,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["verify"],
      );
    } catch {
      throw new HttpError(401, "admin authentication failed");
    }
  }

  async loadCertificates(now, force = false) {
    if (!force && this.certificates && now - this.certificates.loadedAt <= this.cacheMs) return this.certificates;
    const endpoint = `${this.teamDomain}/cdn-cgi/access/certs`;
    let response;
    try {
      response = await this.fetchImpl(endpoint, { headers: { Accept: "application/json" } });
    } catch {
      throw new HttpError(503, "Access certificate verification unavailable");
    }
    if (!response?.ok) throw new HttpError(503, "Access certificate verification unavailable");
    let body;
    try {
      body = await response.json();
    } catch {
      throw new HttpError(503, "Access certificate verification unavailable");
    }
    const keys = Array.isArray(body?.keys) ? body.keys : [];
    if (!keys.length || keys.length > 16) throw new HttpError(503, "Access certificate verification unavailable");
    const safe = keys.filter((item) => item && typeof item === "object" && typeof item.kid === "string");
    if (!safe.length) throw new HttpError(503, "Access certificate verification unavailable");
    return { loadedAt: now, keys: safe };
  }
}

export function createAccessAuthenticator(env = {}, options = {}) {
  const teamDomain = env.MAR_ACCESS_TEAM_DOMAIN || env.CF_ACCESS_TEAM_DOMAIN || "";
  const audience = env.MAR_ACCESS_AUDIENCE || env.CF_ACCESS_AUDIENCE || "";
  if (!teamDomain || !audience) return null;
  return new AccessAuthenticator({ ...options, teamDomain, audience });
}

function normalizeTeamDomain(value) {
  const text = String(value || "").trim().replace(/\/+$/, "");
  if (!/^https:\/\/[^/]+$/i.test(text)) return "";
  return text;
}

function validateClaims(claims, teamDomain, audience, nowMs, skewSeconds) {
  if (!claims || typeof claims !== "object") throw new HttpError(401, "admin authentication failed");
  if (claims.iss !== teamDomain) throw new HttpError(401, "admin authentication failed");
  const audiences = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
  if (!audiences.includes(audience)) throw new HttpError(401, "admin authentication failed");
  const now = Math.floor(nowMs / 1000);
  const skew = Math.max(0, Math.min(300, Number(skewSeconds) || DEFAULT_SKEW_SECONDS));
  if (!Number.isFinite(claims.exp) || claims.exp <= now - skew) throw new HttpError(401, "admin authentication failed");
  if (claims.nbf != null && (!Number.isFinite(claims.nbf) || claims.nbf > now + skew)) {
    throw new HttpError(401, "admin authentication failed");
  }
  if (claims.iat != null && (!Number.isFinite(claims.iat) || claims.iat > now + skew)) {
    throw new HttpError(401, "admin authentication failed");
  }
}

function decodeObject(value) {
  try {
    const decoded = new TextDecoder().decode(base64UrlToBytes(value));
    const result = JSON.parse(decoded);
    if (!result || typeof result !== "object" || Array.isArray(result)) throw new Error("object required");
    return result;
  } catch {
    throw new HttpError(401, "admin authentication failed");
  }
}

function base64UrlToBytes(value) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("invalid base64url");
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (value.length % 4)) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

