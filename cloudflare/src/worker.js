"use strict";

import { DurableObject } from "cloudflare:workers";

const MAX_PACKET_BYTES = 4096;
const MAX_ROOM_LENGTH = 256;
const MAX_UUID_LENGTH = 64;
const MAX_MESSAGES_PER_SECOND = 50;
const RATE_LIMIT_BURST = 15;
const RATE_VIOLATION_WINDOW_MS = 1_000;
const MAX_RATE_VIOLATIONS = 100;
const LOGGING = false;

function log(...args) { if (LOGGING) console.log(...args); }
function logError(...args) { console.error(...args); }
function closeQuietly(ws, code, reason) { try { ws.close(code, reason); } catch {} }

export default {
  async fetch(request, env) {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("ViveCraft WebSocket relay is running.\n", { status: 200 });
    }
    const room = new URL(request.url).searchParams.get("room");
    if (!room || room.length > MAX_ROOM_LENGTH) return new Response("Missing/invalid room", { status: 400 });
    const id = env.RELAY.idFromName(room);
    return env.RELAY.get(id).fetch(request);
  },
};

export class RelayRoom extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.ctx = ctx;
    this.rateLimiters = new Map();
  }

  async fetch(request) {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("WebSocket upgrade required", { status: 426 });
    }
    const room = new URL(request.url).searchParams.get("room");
    if (!room || room.length > MAX_ROOM_LENGTH) return new Response("Missing/invalid room", { status: 400 });
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({ ready: false, room, uuid: null });
    return new Response(null, { status: 101, webSocket: client });
  }

  allowMessage(ws, now = Date.now()) {
    let bucket = this.rateLimiters.get(ws);
    if (!bucket) {
      bucket = {
        tokens: RATE_LIMIT_BURST,
        lastRefill: now,
        violations: 0,
        violationWindowStart: now,
      };
      this.rateLimiters.set(ws, bucket);
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
      closeQuietly(ws, 1008, "rate limit exceeded");
    }
    return false;
  }

  webSocketMessage(ws, message) {
    try {
      const a = ws.deserializeAttachment() || {};
      if (!a.ready) {
        if (typeof message !== "string" || message.length > 1024) return closeQuietly(ws, 1008, "hello required");
        let hello;
        try { hello = JSON.parse(message); } catch { return closeQuietly(ws, 1008, "bad hello"); }
        if (!hello || (hello.protocol !== 1 && hello.protocol !== 2) || hello.type !== "hello" ||
            hello.room !== a.room || typeof hello.uuid !== "string" || hello.uuid.length === 0 || hello.uuid.length > MAX_UUID_LENGTH) {
          return closeQuietly(ws, 1008, "invalid hello");
        }
        const next = { ready: true, room: a.room, uuid: hello.uuid };
        ws.serializeAttachment(next);
        this.sendJson(ws, { type: "ready", protocol: 2, roomSize: this.readyCount() });
        this.announceRoom();
        return;
      }
      if (typeof message === "string") return;
      const size = message instanceof ArrayBuffer ? message.byteLength : message?.byteLength ?? 0;
      if (size === 0) return;
      if (size > MAX_PACKET_BYTES) return closeQuietly(ws, 1009, "packet too large");
      if (!this.allowMessage(ws)) return;
      this.broadcast(a.room, ws, message);
    } catch (err) {
      logError("message handler error", err);
      closeQuietly(ws, 1011, "internal error");
    }
  }

  webSocketClose(ws, code, reason) {
    this.rateLimiters.delete(ws);
    try {
      const a = ws.deserializeAttachment();
      if (a?.ready && a.room && a.uuid) {
        this.broadcast(a.room, ws, JSON.stringify({ type: "leave", uuid: a.uuid }));
        this.announceRoom();
        log("leave", { room: a.room, uuid: a.uuid, code, reason });
      }
    } catch (err) { logError("close handler error", err); }
  }

  webSocketError(ws, error) { this.rateLimiters.delete(ws); logError("socket error", error); closeQuietly(ws, 1011, "socket error"); }

  readyCount() {
    let count = 0;
    for (const ws of this.ctx.getWebSockets()) if (ws.deserializeAttachment()?.ready) count++;
    return count;
  }

  announceRoom() {
    const size = this.readyCount();
    for (const ws of this.ctx.getWebSockets()) if (ws.deserializeAttachment()?.ready) this.sendJson(ws, { type: "room", roomSize: size });
  }

  sendJson(ws, obj) {
    try { ws.send(JSON.stringify(obj)); }
    catch (err) { logError("sendJson failed", err); closeQuietly(ws, 1011, "send failed"); }
  }

  broadcast(room, sender, data) {
    for (const client of this.ctx.getWebSockets()) {
      if (client === sender) continue;
      try {
        const a = client.deserializeAttachment();
        if (!a?.ready || a.room !== room) continue;
        if (client.bufferedAmount > MAX_PACKET_BYTES * 4) continue;
        client.send(data);
      } catch (err) {
        logError("broadcast send failed", err);
        closeQuietly(client, 1011, "send failed");
      }
    }
  }
}
