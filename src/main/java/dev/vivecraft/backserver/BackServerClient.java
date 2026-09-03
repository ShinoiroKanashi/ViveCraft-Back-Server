package dev.vivecraft.backserver;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.vivecraft.api.data.FBTMode;
import org.vivecraft.client.ClientVRPlayers;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.VRState;
import org.vivecraft.client_vr.gameplay.VRPlayer;
import org.vivecraft.client_vr.provider.MCVR;
import org.vivecraft.common.network.Pose;
import org.vivecraft.common.network.VrPlayerState;
import org.vivecraft.common.utils.MathUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BackServerClient implements WebSocket.Listener {
    private static final long MIN_INTERVAL_NANOS = 33_333_333L;
    private static final long MAX_INTERVAL_NANOS = 83_333_333L;
    private static final long DEFAULT_INTERVAL_NANOS = 50_000_000L;
    private static final long RATE_RECALC_NANOS = 500_000_000L;
    private static final long INTERPOLATION_DELAY_NANOS = 70_000_000L;
    private static final double INCOMING_BUDGET_BPS = 120_000.0;
    private static final double OUTGOING_BUDGET_BPS = 80_000.0;

    private final Minecraft mc = Minecraft.getInstance();
    private final HttpClient http = HttpClient.newBuilder().build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ViveCraft-BackServer-Reconnect");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket socket;
    private volatile boolean closing;
    private volatile String room;
    private volatile UUID localUuid;
    private volatile long lastSendNanos;
    private volatile long lastRateCalcNanos;
    private volatile long intervalNanos = DEFAULT_INTERVAL_NANOS;
    private volatile int roomSize = 1;
    private volatile double avgRemotePacketBytes = 180.0;

    private final Map<UUID, RemotePose> remotePlayers = new ConcurrentHashMap<>();
    private final java.io.ByteArrayOutputStream binary = new java.io.ByteArrayOutputStream(512);

    public void onJoin() {
        closing = false;
        localUuid = mc.player != null ? mc.player.getUUID() : null;
        roomSize = 1;
        avgRemotePacketBytes = 180.0;
        intervalNanos = DEFAULT_INTERVAL_NANOS;
        connectSoon(0);
    }

    public void onDisconnect() {
        closing = true;
        clearRemotePlayers();
        intervalNanos = DEFAULT_INTERVAL_NANOS;
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "minecraft disconnect"); } catch (Exception ignored) {}
        }
    }

    public void setEnabled(boolean value) {
        if (BackServerConfig.enabled == value) return;
        BackServerConfig.enabled = value;
        BackServerConfig.save();
        if (value) {
            closing = false;
            if (mc.player != null) {
                localUuid = mc.player.getUUID();
                connectSoon(0);
            }
        } else {
            closing = true;
            clearRemotePlayers();
            WebSocket ws = socket;
            socket = null;
            if (ws != null) {
                try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "disabled via mod menu"); } catch (Exception ignored) {}
            }
        }
    }

    public void tick(Minecraft client) {
        if (!BackServerConfig.enabled || client.player == null || client.level == null) return;

        WebSocket ws = socket;
        if (VRState.VR_RUNNING && ws != null) {
            long now = System.nanoTime();
            if (now - lastRateCalcNanos >= RATE_RECALC_NANOS) {
                recalculateRate(now);
                lastRateCalcNanos = now;
            }
            if (now - lastSendNanos >= intervalNanos) {
                lastSendNanos = now;
                sendPose(ws, client);
            }
        }

        long renderAt = System.nanoTime() - interpolationDelayNanos();
        for (Map.Entry<UUID, RemotePose> entry : remotePlayers.entrySet()) {
            UUID uuid = entry.getKey();
            RemotePose snapshot = entry.getValue();
            if (client.level.getPlayerByUUID(uuid) == null) continue;
            AppliedPose pose = snapshot.sample(renderAt);
            if (pose == null) continue;
            ClientVRPlayers.getInstance().update(uuid, pose.state(), pose.worldScale(), pose.heightScale());
        }
    }

    private void sendPose(WebSocket ws, Minecraft client) {
        try {
            VRPlayer vrPlayer = ClientDataHolderVR.getInstance().vrPlayer;
            VrPlayerState state = createLocalState(vrPlayer);
            float worldScale = vrPlayer.vrdata_world_post.worldScale;
            float heightScale = org.vivecraft.client_vr.settings.AutoCalibration.getPlayerHeight()
                / org.vivecraft.client_vr.settings.AutoCalibration.DEFAULT_HEIGHT;
            byte[] packet = PosePacket.encode(client.player.getUUID(), state, worldScale, heightScale);
            ws.sendBinary(ByteBuffer.wrap(packet), true);
        } catch (Exception ignored) {
        }
    }

    private void recalculateRate(long now) {
        int players = Math.max(1, roomSize);
        int trackers = localTrackerCount();
        int packetBytes = PosePacket.estimateSize(trackers);
        int remotePlayers = Math.max(0, players - 1);
        double remoteBytes = Math.max(117.0, avgRemotePacketBytes);

        double outgoingHz = OUTGOING_BUDGET_BPS / Math.max(1, packetBytes);
        double incomingHz = remotePlayers == 0 ? 30.0 : INCOMING_BUDGET_BPS / (remotePlayers * remoteBytes);
        double crowdCapHz = 30.0 / (1.0 + Math.max(0, players - 8) * 0.12);
        double trackerCapHz = 30.0 - Math.max(0, trackers - 3) * 1.0;

        double targetHz = Math.min(30.0, Math.min(outgoingHz, Math.min(incomingHz, Math.min(crowdCapHz, trackerCapHz))));
        targetHz = Math.max(12.0, targetHz);

        long targetInterval = (long)(1_000_000_000.0 / targetHz);
        intervalNanos = (long)(intervalNanos * 0.65 + targetInterval * 0.35);
        intervalNanos = Math.max(MIN_INTERVAL_NANOS, Math.min(MAX_INTERVAL_NANOS, intervalNanos));
    }

    private int localTrackerCount() {
        if (!VRState.VR_RUNNING) return 0;
        FBTMode mode = ClientDataHolderVR.getInstance().vrPlayer.vrdata_world_post.fbtMode;
        return switch (mode) {
            case ARMS_ONLY -> 0;
            case WITH_JOINTS -> 7;
            default -> 3;
        };
    }

    private long interpolationDelayNanos() {
        long dynamic = intervalNanos + 20_000_000L;
        return Math.max(INTERPOLATION_DELAY_NANOS, Math.min(120_000_000L, dynamic));
    }

    private static VrPlayerState createLocalState(VRPlayer vrPlayer) {
        FBTMode fbtMode = vrPlayer.vrdata_world_post.fbtMode;
        boolean hasFbt = fbtMode != FBTMode.ARMS_ONLY;
        boolean hasExtendedFbt = fbtMode == FBTMode.WITH_JOINTS;
        return new VrPlayerState(
            ClientDataHolderVR.getInstance().vrSettings.seated,
            hmdPose(vrPlayer),
            ClientDataHolderVR.getInstance().vrSettings.reverseHands,
            devicePose(vrPlayer, MCVR.MAIN_CONTROLLER),
            ClientDataHolderVR.getInstance().vrSettings.reverseHands,
            devicePose(vrPlayer, MCVR.OFFHAND_CONTROLLER),
            fbtMode,
            hasFbt ? devicePose(vrPlayer, MCVR.WAIST_TRACKER) : null,
            hasFbt ? devicePose(vrPlayer, MCVR.RIGHT_FOOT_TRACKER) : null,
            hasFbt ? devicePose(vrPlayer, MCVR.LEFT_FOOT_TRACKER) : null,
            hasExtendedFbt ? devicePose(vrPlayer, MCVR.RIGHT_KNEE_TRACKER) : null,
            hasExtendedFbt ? devicePose(vrPlayer, MCVR.LEFT_KNEE_TRACKER) : null,
            hasExtendedFbt ? devicePose(vrPlayer, MCVR.RIGHT_ELBOW_TRACKER) : null,
            hasExtendedFbt ? devicePose(vrPlayer, MCVR.LEFT_ELBOW_TRACKER) : null
        );
    }

    private static Pose hmdPose(VRPlayer vrPlayer) {
        Vector3f position = MathUtils.subtractToVector3f(vrPlayer.vrdata_world_post.hmd.getPosition(), Minecraft.getInstance().player.position());
        Quaternionf orientation = vrPlayer.vrdata_world_post.hmd.getMatrix().getNormalizedRotation(new Quaternionf());
        return new Pose(position, orientation);
    }

    private static Pose devicePose(VRPlayer vrPlayer, int device) {
        Vector3f position = MathUtils.subtractToVector3f(vrPlayer.vrdata_world_post.getDevice(device).getPosition(), Minecraft.getInstance().player.position());
        Quaternionf orientation = vrPlayer.vrdata_world_post.getDevice(device).getMatrix().getNormalizedRotation(new Quaternionf());
        return new Pose(position, orientation);
    }

    private void connectSoon(long delayMs) {
        if (closing || !BackServerConfig.enabled) return;
        scheduler.schedule(this::connect, delayMs, TimeUnit.MILLISECONDS);
    }

    private void connect() {
        if (closing || !BackServerConfig.enabled || socket != null) return;
        try {
            room = computeRoom();
            if (room == null || room.isBlank()) { connectSoon(2000); return; }
            localUuid = mc.player != null ? mc.player.getUUID() : localUuid;
            if (localUuid == null) { connectSoon(2000); return; }
            URI uri = URI.create(BackServerConfig.endpoint + "?room=" + java.net.URLEncoder.encode(room, StandardCharsets.UTF_8));
            http.newWebSocketBuilder().buildAsync(uri, this).whenComplete((ws, error) -> {
                if (error != null) {
                    socket = null;
                    connectSoon(2000);
                }
            });
        } catch (Exception e) {
            connectSoon(2000);
        }
    }

    private String computeRoom() {
        ServerData data = mc.getCurrentServer();
        String server = data != null ? data.ip : null;
        if (server == null || server.isBlank()) server = mc.isLocalServer() ? "integrated" : null;
        if (server == null) return null;
        return "mc-" + sha256(server.toLowerCase());
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Override public void onOpen(WebSocket webSocket) {
        socket = webSocket;
        webSocket.request(1);
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("protocol", 2);
        hello.addProperty("room", room);
        hello.addProperty("uuid", localUuid.toString());
        webSocket.sendText(hello.toString(), true);
    }

    @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            JsonObject o = JsonParser.parseString(data.toString()).getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString() : "";
            if ("ready".equals(type)) {
                roomSize = Math.max(1, o.has("roomSize") ? o.get("roomSize").getAsInt() : 1);
                recalculateRate(System.nanoTime());
            } else if ("leave".equals(type)) {
                UUID uuid = UUID.fromString(o.get("uuid").getAsString());
                remotePlayers.remove(uuid);
                mc.execute(() -> { if (mc.level != null) ClientVRPlayers.getInstance().disableVR(uuid); });
                roomSize = Math.max(1, roomSize - 1);
            } else if ("room".equals(type) && o.has("roomSize")) {
                roomSize = Math.max(1, o.get("roomSize").getAsInt());
                recalculateRate(System.nanoTime());
            }
        } catch (Exception ignored) {}
        webSocket.request(1);
        return null;
    }

    @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        try {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            synchronized (binary) {
                binary.write(bytes);
                if (!last) { webSocket.request(1); return null; }
                byte[] packet = binary.toByteArray();
                binary.reset();
                PosePacket.Decoded decoded = PosePacket.decode(packet);
                if (localUuid != null && localUuid.equals(decoded.uuid())) { webSocket.request(1); return null; }
                long now = System.nanoTime();
                avgRemotePacketBytes = avgRemotePacketBytes * 0.90 + packet.length * 0.10;
                remotePlayers.compute(decoded.uuid(), (id, old) -> {
                    if (old == null) return new RemotePose(decoded, now);
                    old.push(decoded, now);
                    return old;
                });
            }
        } catch (Exception ignored) {
            synchronized (binary) { binary.reset(); }
        }
        webSocket.request(1);
        return null;
    }

    private void clearRemotePlayers() {
        if (remotePlayers.isEmpty()) return;
        UUID[] players = remotePlayers.keySet().toArray(UUID[]::new);
        remotePlayers.clear();
        mc.execute(() -> { for (UUID uuid : players) ClientVRPlayers.getInstance().disableVR(uuid); });
    }

    @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (socket == webSocket) socket = null;
        clearRemotePlayers();
        if (!closing) connectSoon(2000);
        return null;
    }

    @Override public void onError(WebSocket webSocket, Throwable error) {
        if (socket == webSocket) socket = null;
        clearRemotePlayers();
        if (!closing) connectSoon(2000);
    }

    private static final class RemotePose {
        private TimedPose previous;
        private TimedPose latest;
        RemotePose(PosePacket.Decoded first, long time) { latest = new TimedPose(first, time); }
        synchronized void push(PosePacket.Decoded next, long time) {
            previous = latest;
            latest = new TimedPose(next, time);
        }
        synchronized AppliedPose sample(long renderAt) {
            if (latest == null) return null;
            if (previous == null || latest.time <= previous.time) return new AppliedPose(latest.decoded.state(), latest.decoded.worldScale(), latest.decoded.heightScale());
            if (renderAt <= previous.time) return new AppliedPose(previous.decoded.state(), previous.decoded.worldScale(), previous.decoded.heightScale());
            if (renderAt >= latest.time) return new AppliedPose(latest.decoded.state(), latest.decoded.worldScale(), latest.decoded.heightScale());
            float t = (float)((renderAt - previous.time) / (double)(latest.time - previous.time));
            return PoseInterpolator.interpolate(previous.decoded, latest.decoded, t);
        }
    }

    private record TimedPose(PosePacket.Decoded decoded, long time) {}
    private record AppliedPose(VrPlayerState state, float worldScale, float heightScale) {}

    private static final class PoseInterpolator {
        static AppliedPose interpolate(PosePacket.Decoded a, PosePacket.Decoded b, float t) {
            VrPlayerState sa = a.state(), sb = b.state();
            VrPlayerState state = new VrPlayerState(
                sb.seated(), pose(sa.hmd(), sb.hmd(), t), sb.leftHanded(), pose(sa.mainHand(), sb.mainHand(), t),
                sb.leftHanded(), pose(sa.offHand(), sb.offHand(), t), sb.fbtMode(),
                poseNullable(sa.waist(), sb.waist(), t), poseNullable(sa.rightFoot(), sb.rightFoot(), t), poseNullable(sa.leftFoot(), sb.leftFoot(), t),
                poseNullable(sa.rightKnee(), sb.rightKnee(), t), poseNullable(sa.leftKnee(), sb.leftKnee(), t),
                poseNullable(sa.rightElbow(), sb.rightElbow(), t), poseNullable(sa.leftElbow(), sb.leftElbow(), t)
            );
            return new AppliedPose(state, lerp(a.worldScale(), b.worldScale(), t), lerp(a.heightScale(), b.heightScale(), t));
        }
        private static Pose pose(Pose a, Pose b, float t) {
            if (a == null || b == null) return b;
            Vector3f p = new Vector3f(a.position()).lerp(b.position(), t);
            Quaternionf q = new Quaternionf(a.orientation()).slerp(b.orientation(), t).normalize();
            return new Pose(p, q);
        }
        private static Pose poseNullable(Pose a, Pose b, float t) { return b == null ? null : (a == null ? b : pose(a, b, t)); }
        private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    }
}
