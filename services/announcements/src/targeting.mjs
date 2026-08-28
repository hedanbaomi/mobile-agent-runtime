// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
import { rolloutBucket } from "./rollout.mjs";

export const PLATFORMS = new Set(["android", "desktop", "ios", "all"]);
export const CHANNELS = new Set(["stable", "beta", "nightly", "all"]);
const TOKEN = /^[A-Za-z0-9._-]+$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export function isInstallId(value) {
  return typeof value === "string" && UUID.test(value);
}

export function isToken(value) {
  return typeof value === "string" && TOKEN.test(value);
}

export function localeFallback(requested) {
  const parts = String(requested || "default").split("-");
  const chain = [requested];
  if (parts.length >= 3) chain.push(`${parts[0]}-${parts[2]}`);
  if (parts.length >= 2) chain.push(`${parts[0]}-${parts[1]}`);
  chain.push(parts[0], "default");
  return [...new Set(chain.filter(Boolean))];
}

export function targetingMatches(target, client, announcementId) {
  if (target.platform !== "all" && target.platform !== client.platform) return false;
  if (target.channel !== "all" && target.channel !== client.channel) return false;
  if (target.minVersionCode != null && client.versionCode < target.minVersionCode) return false;
  if (target.maxVersionCode != null && client.versionCode > target.maxVersionCode) return false;
  if (Array.isArray(target.locales) && target.locales.length > 0 && !target.locales.includes(client.locale)) {
    return false;
  }
  const salt = target.rolloutSalt || "default";
  const percent = target.rolloutPercent ?? 100;
  if (!isToken(announcementId) || !isToken(salt) || !isInstallId(client.installId)) return false;
  if (percent < 0 || percent > 100) return false;
  return rolloutBucket(announcementId, salt, client.installId) < percent;
}

export function pickTranslation(translations, locale) {
  const map = translations || {};
  for (const candidate of localeFallback(locale)) {
    if (map[candidate]) return { locale: candidate, ...map[candidate] };
  }
  return null;
}
