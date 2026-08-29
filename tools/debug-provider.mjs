// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

// Explicit local protocol fixture. Never use this as a model-quality result.
import http from "node:http";
import { appendFileSync } from "node:fs";

const port = Number(process.argv[2] || 8765);
const reportPath = process.argv[3];
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw Error("Invalid test port");
const testKey = "qa-only-no-real-secret-20260829";
let sequence = 0;
const record = (event) => {
  const line = JSON.stringify({ at: new Date().toISOString(), ...event });
  if (reportPath) appendFileSync(reportPath, line + "\n", "utf8");
  console.log(line);
};
const text = (message) => typeof message.content === "string" ? message.content
  : (message.content || []).filter((part) => part.type === "text").map((part) => part.text).join("\n");

const server = http.createServer(async (req, res) => {
  const id = ++sequence;
  if (req.method !== "POST" || req.url !== "/v1/chat/completions") {
    res.writeHead(404).end(); return;
  }
  let bytes = 0;
  const chunks = [];
  for await (const chunk of req) {
    bytes += chunk.length;
    if (bytes > 1048576) { res.writeHead(413).end(); return; }
    chunks.push(chunk);
  }
  let request;
  try { request = JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch { res.writeHead(400).end(); return; }
  const authorized = req.headers.authorization === `Bearer ${testKey}`;
  record({ id, event: "request", authorized, bytes, model: request.model,
    roles: request.messages?.map((item) => item.role),
    toolNames: request.tools?.map((item) => item.function?.name),
    parameterKeys: Object.keys(request).filter((key) => !["messages", "tools"].includes(key)),
    maxTokens: request.max_tokens ?? request.max_completion_tokens });
  if (!authorized) { res.writeHead(401).end('{"error":{"message":"Local fixture key mismatch"}}'); return; }
  const messages = request.messages || [];
  const lastUser = messages.findLastIndex((item) => item.role === "user");
  const userText = lastUser < 0 ? "" : text(messages[lastUser]);
  if (userText.includes("QA_ERROR")) {
    res.writeHead(500, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: { message: `LOCAL QA FIXTURE intentional error; ${testKey}` } }));
    record({ id, event: "intentional_error" }); return;
  }
  res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-store" });
  let ended = false;
  const delta = (value, finish_reason = null) => res.write(`data: ${JSON.stringify({ id: `qa-${id}`, object: "chat.completion.chunk", choices: [{ index: 0, delta: value, finish_reason }] })}\n\n`);
  const done = () => {
    if (ended) return;
    ended = true;
    res.write('data: {"choices":[],"usage":{"prompt_tokens":32,"completion_tokens":24}}\n\n');
    res.end("data: [DONE]\n\n");
    record({ id, event: "completed" });
  };
  const toolAfterUser = messages.slice(lastUser + 1).some((item) => item.role === "tool");
  if (userText.includes("QA_CALCULATE") && !toolAfterUser && request.tools?.some((tool) => tool.function?.name === "calculator")) {
    delta({ tool_calls: [{ index: 0, id: `qa-calculator-${id}`, type: "function", function: { name: "calculator", arguments: '{"expression":' } }] });
    delta({ tool_calls: [{ index: 0, function: { arguments: '"12+30"}' } }] }, "tool_calls");
    done(); return;
  }
  if (userText.includes("QA_HOLD")) {
    let ticks = 0;
    const interval = setInterval(() => {
      if (res.destroyed) { clearInterval(interval); return; }
      delta({ content: `LOCAL QA STREAM ${++ticks}. ` });
      if (ticks >= 120) { clearInterval(interval); done(); }
    }, 250);
    res.on("close", () => { clearInterval(interval); record({ id, event: "connection_closed", completed: ended }); });
    return;
  }
  const reply = toolAfterUser
    ? "LOCAL QA FIXTURE: calculator tool completed. Expected result: 42. This is a controlled protocol test."
    : "LOCAL QA FIXTURE: streaming works. This response comes from a local test server, not a real AI model.";
  for (const part of reply.match(/.{1,18}/g) || []) delta({ content: part });
  delta({}, "stop"); done();
});
server.listen(port, "127.0.0.1", () => record({ event: "listening", host: "127.0.0.1", port, fixture: true }));
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => server.close(() => process.exit(0)));
