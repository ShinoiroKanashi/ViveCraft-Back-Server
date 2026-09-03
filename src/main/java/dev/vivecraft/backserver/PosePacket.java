package dev.vivecraft.backserver;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.vivecraft.api.data.FBTMode;
import org.vivecraft.common.network.Pose;
import org.vivecraft.common.network.VrPlayerState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

public final class PosePacket {
    public static final int MAGIC = 0x56434253;
    public static final byte VERSION = 1;
    public static final byte TYPE_POSE = 1;

    private static final int HMD = 0;
    private static final int MAIN = 1;
    private static final int OFF = 2;
    private static final int WAIST = 3;
    private static final int RIGHT_FOOT = 4;
    private static final int LEFT_FOOT = 5;
    private static final int RIGHT_KNEE = 6;
    private static final int LEFT_KNEE = 7;
    private static final int RIGHT_ELBOW = 8;
    private static final int LEFT_ELBOW = 9;

    private PosePacket() {}

    public static int estimateSize(int trackerCount) {
        int poses = trackerCount >= 7 ? 10 : (trackerCount >= 3 ? 6 : 3);
        return 33 + poses * 28;
    }

    public static byte[] encode(UUID uuid, VrPlayerState state, float worldScale, float heightScale) {
        int mask = 0b111;
        if (state.fbtMode() != FBTMode.ARMS_ONLY) {
            mask |= (1 << WAIST) | (1 << RIGHT_FOOT) | (1 << LEFT_FOOT);
        }
        if (state.fbtMode() == FBTMode.WITH_JOINTS) {
            mask |= (1 << RIGHT_KNEE) | (1 << LEFT_KNEE) |
                    (1 << RIGHT_ELBOW) | (1 << LEFT_ELBOW);
        }

        int poseCount = Integer.bitCount(mask);
        ByteBuffer b = ByteBuffer.allocate(4 + 1 + 1 + 16 + 4 + 4 + 1 + 2 + poseCount * 28)
            .order(ByteOrder.BIG_ENDIAN);

        b.putInt(MAGIC);
        b.put(VERSION);
        b.put(TYPE_POSE);
        b.putLong(uuid.getMostSignificantBits());
        b.putLong(uuid.getLeastSignificantBits());
        b.putFloat(worldScale);
        b.putFloat(heightScale);

        int flags = (state.seated() ? 1 : 0) |
                    (state.leftHanded() ? 2 : 0) |
                    (state.fbtMode().ordinal() << 2);
        b.put((byte) flags);
        b.putShort((short) mask);

        put(b, state.hmd());
        put(b, state.mainHand());
        put(b, state.offHand());

        if ((mask & (1 << WAIST)) != 0) put(b, state.waist());
        if ((mask & (1 << RIGHT_FOOT)) != 0) put(b, state.rightFoot());
        if ((mask & (1 << LEFT_FOOT)) != 0) put(b, state.leftFoot());
        if ((mask & (1 << RIGHT_KNEE)) != 0) put(b, state.rightKnee());
        if ((mask & (1 << LEFT_KNEE)) != 0) put(b, state.leftKnee());
        if ((mask & (1 << RIGHT_ELBOW)) != 0) put(b, state.rightElbow());
        if ((mask & (1 << LEFT_ELBOW)) != 0) put(b, state.leftElbow());

        return b.array();
    }

    private static void put(ByteBuffer b, Pose p) {
        Vector3fc v = p.position();
        Quaternionfc q = p.orientation();
        b.putFloat(v.x()).putFloat(v.y()).putFloat(v.z());
        b.putFloat(q.w()).putFloat(q.x()).putFloat(q.y()).putFloat(q.z());
    }

    public record Decoded(UUID uuid, VrPlayerState state, float worldScale, float heightScale) {}

    public static Decoded decode(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (b.remaining() < 33) throw new IllegalArgumentException("packet too short");
        if (b.getInt() != MAGIC) throw new IllegalArgumentException("bad magic");
        if (b.get() != VERSION) throw new IllegalArgumentException("bad protocol");
        if (b.get() != TYPE_POSE) throw new IllegalArgumentException("bad packet type");

        UUID uuid = new UUID(b.getLong(), b.getLong());
        float worldScale = b.getFloat();
        float heightScale = b.getFloat();
        int flags = b.get() & 0xFF;
        int mask = b.getShort() & 0xFFFF;

        if ((mask & 0b111) != 0b111) throw new IllegalArgumentException("missing mandatory pose");

        boolean seated = (flags & 1) != 0;
        boolean leftHanded = (flags & 2) != 0;
        int fbtOrdinal = (flags >>> 2) & 3;
        FBTMode[] modes = FBTMode.values();
        if (fbtOrdinal < 0 || fbtOrdinal >= modes.length) throw new IllegalArgumentException("bad FBT mode");
        FBTMode fbt = modes[fbtOrdinal];

        Pose hmd = read(b);
        Pose main = read(b);
        Pose off = read(b);
        Pose waist = ((mask & (1 << WAIST)) != 0) ? read(b) : null;
        Pose rightFoot = ((mask & (1 << RIGHT_FOOT)) != 0) ? read(b) : null;
        Pose leftFoot = ((mask & (1 << LEFT_FOOT)) != 0) ? read(b) : null;
        Pose rightKnee = ((mask & (1 << RIGHT_KNEE)) != 0) ? read(b) : null;
        Pose leftKnee = ((mask & (1 << LEFT_KNEE)) != 0) ? read(b) : null;
        Pose rightElbow = ((mask & (1 << RIGHT_ELBOW)) != 0) ? read(b) : null;
        Pose leftElbow = ((mask & (1 << LEFT_ELBOW)) != 0) ? read(b) : null;

        if (b.hasRemaining()) throw new IllegalArgumentException("trailing packet data");

        VrPlayerState state = new VrPlayerState(
            seated, hmd, leftHanded, main, leftHanded, off,
            fbt, waist, rightFoot, leftFoot,
            rightKnee, leftKnee, rightElbow, leftElbow
        );

        return new Decoded(uuid, state, worldScale, heightScale);
    }

    private static Pose read(ByteBuffer b) {
        Vector3f p = new Vector3f(b.getFloat(), b.getFloat(), b.getFloat());
        float w = b.getFloat();
        float x = b.getFloat();
        float y = b.getFloat();
        float z = b.getFloat();
        return new Pose(p, new Quaternionf(x, y, z, w));
    }
}
