// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { createWorker } from "./app.mjs";
import { D1Store } from "./d1-store.mjs";
import { audienceHash, generateKeyPair } from "./sign.mjs";
import { MemoryStore } from "./store.mjs";

const NOW = new Date("2026-08-29T12:00:00.000Z");
const INSTALLS = {
  day: "00000000-0000-4000-8000-000000000101",
  week: "00000000-0000-4000-8000-000000000102",
  month: "00000000-0000-4000-8000-000000000103",
  stale: "00000000-0000-4000-8000-000000000104",
};

function event(id, type, installId, receivedAt, dimensions) {
  return {
    eventId: `00000000-0000-4000-8000-${String(id).padStart(12, "0")}`,
    type,
    installId,
    installIdHash: audienceHash(installId),
    ...dimensions,
    occurredAt: receivedAt,
  };
}

function recordAt(store, installId, receivedAt, dimensions, suffix) {
  const now = new Date(receivedAt);
  store.recordEvents([
    event(`${suffix}1`, "install_seen", installId, receivedAt, dimensions),
    event(`${suffix}2`, "app_active", installId, receivedAt, dimensions),
  ], now);
}

{
  const store = new MemoryStore();
  recordAt(store, INSTALLS.day, "2026-08-29T11:00:00.000Z", {
    platform: "android", channel: "stable", versionCode: 101, locale: "en",
  }, "11");
  recordAt(store, INSTALLS.week, "2026-08-27T11:00:00.000Z", {
    platform: "android", channel: "beta", versionCode: 202, locale: "en",
  }, "12");
  recordAt(store, INSTALLS.month, "2026-08-19T11:00:00.000Z", {
    platform: "desktop", channel: "nightly", versionCode: 303, locale: "zh-CN",
  }, "13");
  recordAt(store, INSTALLS.stale, "2026-07-01T11:00:00.000Z", {
    platform: "ios", channel: "stable", versionCode: 404, locale: "en",
  }, "14");

  const stats = store.stats(NOW);
  assert.equal(stats.consentedInstalls, 4);
  assert.equal(stats.installSeen, 4);
  assert.equal(stats.appActive, 4);
  assert.equal(stats.dau, 1);
  assert.equal(stats.wau, 2);
  assert.equal(stats.mau, 3);
  assert.doesNotMatch(JSON.stringify(stats), /installIdHash|eventId|occurredAt|installId/);
  assert.deepEqual(stats.byVersion, [
    { versionCode: 101, count: 1 },
    { versionCode: 202, count: 1 },
    { versionCode: 303, count: 1 },
  ]);
  assert.deepEqual(stats.byChannel, [
    { channel: "beta", count: 1 },
    { channel: "nightly", count: 1 },
    { channel: "stable", count: 1 },
  ]);
  assert.deepEqual(stats.byPlatform, [
    { platform: "android", count: 2 },
    { platform: "desktop", count: 1 },
  ]);
  console.log("MemoryStore install_seen/app_active DAU/WAU/MAU distributions ok");
}

{
  const worker = createWorker({
    store: new MemoryStore(),
    keys: generateKeyPair(),
    adminToken: "stats-test-token",
    clock: () => NOW,
  });
  const events = [
    event("301", "install_seen", INSTALLS.day, NOW.toISOString(), { platform: "android", channel: "stable", versionCode: 501, locale: "en" }),
    event("302", "app_active", INSTALLS.day, NOW.toISOString(), { platform: "android", channel: "stable", versionCode: 501, locale: "en" }),
  ];
  const accepted = await worker.fetch(new Request("http://127.0.0.1:8787/api/v1/events", {
    method: "POST",
    headers: { "content-type": "application/json", "X-Stats-Consent": "1" },
    body: JSON.stringify({ events }),
  }));
  assert.equal(accepted.status, 204);
  const response = await worker.fetch(new Request("http://127.0.0.1:8787/admin/v1/stats", {
    headers: { "X-Admin-Token": "stats-test-token" },
  }));
  assert.equal(response.status, 200);
  const stats = await response.json();
  assert.equal(stats.installSeen, 1);
  assert.equal(stats.appActive, 1);
  assert.equal(stats.dau, 1);
  assert.deepEqual(stats.byVersion, [{ versionCode: 501, count: 1 }]);
  console.log("Worker stats endpoint exposes aggregate dimensions ok");
}

{
  const store = new MemoryStore();
  assert.throws(() => store.recordEvents([{
    eventId: "00000000-0000-4000-8000-000000000201",
    type: "app_active",
    installIdHash: "a".repeat(64),
    platform: "android",
    channel: "stable",
    versionCode: 1,
    locale: "prompt",
  }], NOW), /forbidden content/);
  assert.throws(() => store.recordEvents([{
    eventId: "00000000-0000-4000-8000-000000000202",
    type: "app_active",
    installIdHash: "b".repeat(64),
    platform: "android",
    channel: "stable",
    versionCode: 1,
    locale: "en",
    announcementId: "knowledge",
  }], NOW), /forbidden content/);
  console.log("stats sensitive content rejection remains enforced");
}

{
  const admin = readFileSync(new URL("../../../admin/announcements/index.html", import.meta.url), "utf8");
  const semanticText = [];
  const semanticPattern = /<(title|h1|h2|label|button|summary|legend|strong|p)\b[^>]*>([\s\S]*?)<\/\1>/gi;
  for (const match of admin.matchAll(semanticPattern)) {
    const text = match[2].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
    if (text) semanticText.push(text);
  }
  for (const text of admin.matchAll(/\baria-label="([^"]+)"/gi)) semanticText.push(text[1]);
  assert.match(admin, /<html lang="zh-CN">/);
  assert.ok(semanticText.length > 0);
  for (const text of semanticText) assert.match(text, /[\u3400-\u9fff]/, `可见管理端文案缺少简体中文：${text}`);
  for (const [, value] of admin.matchAll(/\bplaceholder="([^"]+)"/gi)) {
    if (!/^\d{4}-\d{2}-\d{2}T[\d:.]+Z$/.test(value)) assert.match(value, /[\u3400-\u9fff]/, `输入提示缺少简体中文：${value}`);
  }

  const staleVisibleEnglish = [
    "Announcements administration", "Local test authentication", "Admin token (local loopback only)",
    "Draft editor", "Default title", "Default summary", "Default body Markdown", "HTTPS action URL (optional)",
    "Advanced fields /", "Category", "Severity", "Display mode", "Rollout percent", "Target platform", "Target channel",
    "Minimum version code", "Maximum version code", "Rollout salt", "Target locales (comma separated)", "Starts at (UTC ISO)",
    "Ends at (UTC ISO)", "Must acknowledge", "Pinned", "Create draft", "New revision", "Schedule", "Publish", "Withdraw",
    "Archive", "Android preview", "Announcement preview", "Reload list", "Anonymous activity statistics", "Reload stats",
    "Access is required before production writes are enabled.", "The API returned an unavailable or unauthorized response.",
    "Do not paste a production token here.",
  ];
  const visibleText = semanticText.join("\n");
  for (const text of staleVisibleEnglish) assert.equal(visibleText.includes(text), false, `检测到未翻译的管理端文案：${text}`);
  for (const text of ["普通公告 / General", "重要公告 / Important", "版本更新 / Version update", "General announcement", "Please read", "Announcement body.", "Important notice", "Update available", "Open app updater", "I have read and acknowledge", "Open link", "Dismiss"]) {
    assert.equal(admin.includes(text), false, `检测到未翻译的动态管理端文案：${text}`);
  }

  const optionsFor = (id) => {
    const select = admin.match(new RegExp(`<select id="${id}"[^>]*>([\\s\\S]*?)</select>`));
    assert.ok(select, `缺少选择器：${id}`);
    return [...select[1].matchAll(/<option\b[^>]*value="([^"]+)"[^>]*>([^<]*)<\/option>/g)].map(([, value, label]) => [value, label]);
  };
  assert.deepEqual(optionsFor("category"), [
    ["GENERAL", "常规"], ["FEATURE", "功能"], ["MAINTENANCE", "维护"], ["SERVICE_INCIDENT", "服务故障"],
    ["UPDATE", "更新"], ["SECURITY", "安全"], ["DEPRECATION", "弃用"],
  ]);
  assert.deepEqual(optionsFor("severity"), [["INFO", "信息"], ["NOTICE", "通知"], ["WARNING", "警告"], ["CRITICAL", "严重"]]);
  assert.deepEqual(optionsFor("displayMode"), [["CENTER_ONLY", "居中展示"], ["BANNER", "横幅"], ["MODAL", "弹窗"]]);
  assert.deepEqual(optionsFor("targetPlatform"), [["android", "安卓"], ["desktop", "桌面端"], ["ios", "iOS（苹果系统）"], ["all", "全部"]]);
  assert.deepEqual(optionsFor("targetChannel"), [["all", "全部"], ["stable", "稳定版"], ["beta", "测试版"], ["nightly", "夜间版"]]);

  for (const text of ["常规公告", "重要通知", "有新版本可用", "这是公告正文。", "这是重要公告正文。", "打开应用更新"]) {
    assert.ok(admin.includes(text), `缺少中文预设或预览文案：${text}`);
  }
  for (const prefix of ["服务器响应：", "服务器返回的公告数据：", "服务器返回的统计数据：", "请先加载现有公告，以便校验预期修订号。", "展示方式："]) {
    assert.ok(admin.includes(prefix), `缺少中文状态前缀或提示：${prefix}`);
  }
  for (const [key, label] of [
    ["items", "公告条目"], ["currentPublishedRevision", "当前已发布修订"], ["pendingRevision", "待发布修订"], ["revisionStatus", "修订状态"],
    ["consentedInstalls", "已同意统计的安装数"], ["receiptRows", "回执记录数"], ["installSeen", "已记录安装数"], ["appActive", "活跃安装数"],
    ["dau", "日活跃安装数"], ["wau", "周活跃安装数"], ["mau", "月活跃安装数"], ["byVersion", "按版本统计"], ["byChannel", "按渠道统计"],
    ["byPlatform", "按平台统计"], ["versionCode", "版本号"], ["channel", "渠道"], ["platform", "平台"], ["count", "数量"],
  ]) {
    assert.ok(admin.includes(`${key}:"${label}"`), `缺少动态字段中文标签：${key}`);
  }
  assert.match(admin, /function localizeAdminData\(value, field = ""\)/);
  assert.match(admin, /function formatAdminResponse\(text, prefix\)/);
  assert.match(admin, /catch \{ return prefix \+ "\\n原始响应：\\n" \+ text; \}/);

  const script = admin.match(/<script>([\s\S]*?)<\/script>/)?.[1];
  assert.ok(script, "缺少管理端脚本");
  const elements = new Map();
  const element = (id) => {
    if (!elements.has(id)) elements.set(id, { id, value:"", checked:false, hidden:false, disabled:false, textContent:"", append() {} });
    return elements.get(id);
  };
  const context = {
    location:{ origin:"http://127.0.0.1:8787", hostname:"127.0.0.1" },
    document:{ getElementById:element, createElement:(tag) => ({ tag, type:"", disabled:false, textContent:"", append() {} }) },
    crypto:{ randomUUID:() => "00000000-0000-4000-8000-000000000000" },
    fetch:async () => { throw new Error("测试不应联网"); },
  };
  const runtimeSample = {
    items:[{ status:"published", pendingRevision:null, revisions:[{ revisionStatus:"superseded" }], body:{
      category:"SECURITY", severity:"WARNING", displayMode:"MODAL", mustAcknowledge:true,
      target:{ platform:"android", channel:"stable" }, actions:[{ type:"OPEN_APP_ROUTE" }],
    } }],
    byChannel:[{ channel:"nightly", count:1 }], byPlatform:[{ platform:"desktop", count:1 }],
  };
  const executable = script.replace(/\s*applyPreset\("general"\);\s*$/, "") +
    `\nglobalThis.__localized = localizeAdminData(${JSON.stringify(runtimeSample)});` +
    `\nglobalThis.__formatted = formatAdminResponse(${JSON.stringify(JSON.stringify(runtimeSample))}, "服务器数据：");`;
  runInNewContext(executable, context);
  assert.equal(context.__localized["公告条目"][0]["状态"], "已发布");
  assert.equal(context.__localized["公告条目"][0]["待发布修订"], "无");
  assert.equal(context.__localized["公告条目"][0]["修订记录"][0]["修订状态"], "已被新修订替代");
  assert.equal(context.__localized["公告条目"][0]["公告内容"]["类别"], "安全");
  assert.equal(context.__localized["公告条目"][0]["公告内容"]["严重程度"], "警告");
  assert.equal(context.__localized["公告条目"][0]["公告内容"]["展示方式"], "弹窗");
  assert.equal(context.__localized["公告条目"][0]["公告内容"]["需要确认"], "是");
  assert.equal(context.__localized["公告条目"][0]["公告内容"]["目标"]["平台"], "安卓");
  assert.equal(context.__localized["公告条目"][0]["公告内容"]["目标"]["渠道"], "稳定版");
  assert.equal(context.__localized["公告条目"][0]["公告内容"]["操作"][0]["操作类型"], "打开应用内页面");
  assert.equal(context.__localized["按渠道统计"][0]["渠道"], "夜间版");
  assert.equal(context.__localized["按平台统计"][0]["平台"], "桌面端");
  for (const wireValue of ["published", "superseded", "SECURITY", "WARNING", "MODAL", "android", "stable", "nightly", "desktop", "OPEN_APP_ROUTE"]) {
    assert.equal(context.__formatted.includes(`\"${wireValue}\"`), false, `显示结果泄漏英文协议值：${wireValue}`);
  }
  assert.match(admin, /<details[^>]*id="advancedFields"/);
  assert.match(admin, /OPEN_APP_ROUTE/);
  assert.match(admin, /app:\/\/update/);
  console.log("admin UI semantic copy, translated option labels, and update route contract ok");
}

{
  const responses = new Map([
    ["SELECT COUNT(*) AS count FROM install_state", { count: 4 }],
    ["SELECT COUNT(*) AS count FROM announcement_receipts", { count: 8 }],
    ["FROM announcement_receipts", { install_seen: 4, app_active: 4 }],
    ["COUNT(DISTINCT CASE", { dau: 1, wau: 2, mau: 3 }],
  ]);
  const grouped = new Map([
    ["GROUP BY version_code", [{ version_code: 101, count: 1 }, { version_code: 202, count: 1 }, { version_code: 303, count: 1 }]],
    ["GROUP BY channel", [{ channel: "beta", count: 1 }, { channel: "nightly", count: 1 }, { channel: "stable", count: 1 }]],
    ["GROUP BY platform", [{ platform: "android", count: 2 }, { platform: "desktop", count: 1 }]],
  ]);
  const db = {
    prepare(sql) {
      const statement = {
        bind() { return statement; },
        async first() {
          for (const [key, value] of responses) if (sql.includes(key)) return value;
          throw new Error(`unexpected D1 first SQL: ${sql}`);
        },
        async all() {
          for (const [key, value] of grouped) if (sql.includes(key)) return { results: value };
          throw new Error(`unexpected D1 all SQL: ${sql}`);
        },
      };
      return statement;
    },
    async batch() { return []; },
  };
  const stats = await new D1Store(db, { keyId: "local-dev-1" }).stats(NOW);
  assert.equal(stats.installSeen, 4);
  assert.equal(stats.appActive, 4);
  assert.equal(stats.dau, 1);
  assert.equal(stats.wau, 2);
  assert.equal(stats.mau, 3);
  assert.deepEqual(stats.byVersion[0], { versionCode: 101, count: 1 });
  assert.deepEqual(stats.byChannel[1], { channel: "nightly", count: 1 });
  assert.deepEqual(stats.byPlatform[0], { platform: "android", count: 2 });
  console.log("D1Store stats query exposes event types, active windows, and dimensions ok");
}
