"use strict";

require("dotenv").config();
const WebSocket = require("ws");

const PORT = Number(process.env.PORT || 8765);
const HOST = process.env.HOST || "0.0.0.0";
const LOGGING = (process.env.LOGGING ?? "false").toLowerCase() === "true";
const MAX_PACKET_BYTES = 4096;
const HEARTBEAT_INTERVAL_MS = 30_000;
const MAX_MESSAGES_PER_SECOND = 50;
const RATE_LIMIT_BURST = 15;
const RATE_VIOLATION_WINDOW_MS = 1_000;
const MAX_RATE_VIOLATIONS = 100;
const MAX_ROOM_LENGTH = 256;
const MAX_UUID_LENGTH = 64;
const rooms = new Map();

function log(...args) { if (LOGGING) console.log(`[${new Date().toISOString()}]`, ...args); }
function logError(...args) { console.error(`[${new Date().toISOString()}]`, ...args); }
function roomSet(room) { let s = rooms.get(room); if (!s) rooms.set(room, s = new Set()); return s; }
function sendJson(ws, obj) { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj)); }

function allowMessage(ws, now = Date.now()) {
  let bucket = ws.rateLimit;
  if (!bucket) {
    bucket = ws.rateLimit = {
      tokens: RATE_LIMIT_BURST,
      lastRefill: now,
      violations: 0,
      violationWindowStart: now,
    };
  }
  const elapsed = Math.max(0, now - bucket.lastRefill);
  bucket.tokens = Math.min(
    RATE_LIMIT_BURST,
    bucket.tokens + (elapsed * MAX_MESSAGES_PER_SECOND) / 1_000,
  );
  bucket.lastRefill = now;
  if (bucket.tokens >= 1) {
    bucket.tokens -= 1;
    return true;
  }
  if (now - bucket.violationWindowStart >= RATE_VIOLATION_WINDOW_MS) {
    bucket.violationWindowStart = now;
    bucket.violations = 0;
  }
  bucket.violations += 1;
  if (bucket.violations >= MAX_RATE_VIOLATIONS) {
    try { ws.close(1008, "rate limit exceeded"); } catch {}
  }
  return false;
}

function broadcast(room, sender, data, binary = true) {
  const set = rooms.get(room); if (!set) return;
  for (const client of set) {
    if (client === sender || client.readyState !== WebSocket.OPEN) continue;
    if (client.bufferedAmount > MAX_PACKET_BYTES * 4) continue;
    try { client.send(data, { binary }); } catch (e) { logError("broadcast", e); }
  }
}
function announceRoom(room) {
  const set = rooms.get(room); if (!set) return;
  const size = set.size;
  for (const client of set) sendJson(client, { type: "room", roomSize: size });
}
function leave(ws) {
  const room = ws.room;
  if (!room) return;
  const set = rooms.get(room);
  if (set) {
    set.delete(ws);
    broadcast(room, ws, JSON.stringify({ type: "leave", uuid: ws.uuid }), false);
    if (set.size === 0) rooms.delete(room); else announceRoom(room);
  }
  ws.room = null;
}

process.on("uncaughtException", e => logError("uncaughtException", e));
process.on("unhandledRejection", e => logError("unhandledRejection", e));

const wss = new WebSocket.Server({
  port: PORT,
  host: HOST,
  maxPayload: MAX_PACKET_BYTES,
  perMessageDeflate: false,
  clientTracking: true,
});

wss.on("connection", (ws, req) => {
  ws.isReady = false;
  ws.room = null;
  ws.uuid = null;
  ws.isAlive = true;
  ws.rateLimit = null;
  log("connection", req.socket?.remoteAddress);

  ws.on("pong", () => { ws.isAlive = true; });
  ws.on("message", (data, isBinary) => {
    try {
      if (!ws.isReady) {
        if (isBinary || data.length > 1024) return ws.close(1008, "hello required");
        let hello;
        try { hello = JSON.parse(data.toString("utf8")); } catch { return ws.close(1008, "bad hello"); }
        if (!hello || (hello.protocol !== 1 && hello.protocol !== 2) || hello.type !== "hello" ||
            typeof hello.room !== "string" || typeof hello.uuid !== "string" ||
            hello.room.length === 0 || hello.room.length > MAX_ROOM_LENGTH ||
            hello.uuid.length === 0 || hello.uuid.length > MAX_UUID_LENGTH) {
          return ws.close(1008, "invalid hello");
        }
        ws.room = hello.room;
        ws.uuid = hello.uuid;
        ws.protocolVersion = hello.protocol;
        ws.isReady = true;
        const set = roomSet(ws.room);
        set.add(ws);
        sendJson(ws, { type: "ready", protocol: 2, roomSize: set.size });
        announceRoom(ws.room);
        log("join", { room: ws.room, uuid: ws.uuid, size: set.size });
        return;
      }
      if (!isBinary) return;
      if (data.length > MAX_PACKET_BYTES) return ws.close(1009, "packet too large");
      if (!allowMessage(ws)) return;
      broadcast(ws.room, ws, data, true);
    } catch (e) { logError("message", e); }
  });
  ws.on("close", () => { try { leave(ws); } catch (e) { logError("close", e); } });
  ws.on("error", e => { logError("socket", e); try { leave(ws); } catch {} });
});

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) { ws.terminate(); continue; }
    ws.isAlive = false;
    try { ws.ping(); } catch {}
  }
}, HEARTBEAT_INTERVAL_MS);

function shutdown(signal) {
  clearInterval(heartbeat);
  for (const ws of wss.clients) { try { ws.close(1001, "server shutting down"); } catch {} }
  wss.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 2000).unref();
}
process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));
console.log(`ViveCraft Back Server relay listening on ws://${HOST}:${PORT}`);
