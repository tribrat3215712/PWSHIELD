package pzshield;

import java.nio.ByteBuffer;
import org.joml.Quaternionf;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.physics.Transform;
import zombie.vehicles.TransmissionNumber;
import pzshield.PacketGuards;

/** Independent wire checks using original 42.21 Reader/Writer and JOML. No RakNet/Bullet/server startup. */
public final class ProtocolIntegrationTests {
    static int checks;
    static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
    static ByteBuffer wire(int wheels, TransmissionNumber transmission) {
        ByteBuffer b = ByteBuffer.allocate(69 + wheels * 16);
        ByteBufferWriter writer = new ByteBufferWriter(b);
        writer.putShort(Short.MIN_VALUE); // signed vehicle ID is legal except -1
        writer.putLong(123456789012L);
        for (int i = 0; i < 13; i++) writer.putFloat(i == 7 ? 1f : i);
        writer.putEnum(transmission);
        writer.putShort((short) wheels);
        for (int i = 0; i < wheels * 4; i++) writer.putFloat(i + 0.5f);
        writer.putFloat(-23.5f); // reversing speed is legal
        b.flip();
        return b;
    }
    public static void main(String[] args) {
        boolean corrected = true;
        for (int wheels = 0; wheels <= 4; wheels++) {
            for (TransmissionNumber transmission : TransmissionNumber.values()) {
                ByteBuffer b = wire(wheels, transmission);
                check(PacketGuards.vehicle(b, TransmissionNumber.values().length), "real writer accepted");
                check(b.position() == 0, "guard non-consuming");
                check(b.get(62) == transmission.ordinal(), "enum ordinal encoded at byte 62");
                ByteBufferReader reader = new ByteBufferReader(b);
                check(reader.getShort() == Short.MIN_VALUE, "signed IDShort preserved");
                check(reader.getLong() == 123456789012L, "timestamp at byte 2");
                for (int i = 0; i < 13; i++) check(reader.getFloat() == (i == 7 ? 1f : i), "float wire order");
                check(reader.getEnum(TransmissionNumber.class) == transmission, "unsigned enum ordinal");
                check(reader.getShort() == wheels, "wheel count at byte 63");
                for (int i = 0; i < wheels * 4; i++) check(reader.getFloat() == i + 0.5f, "wheel field");
                check(reader.getFloat() == -23.5f && reader.bb.remaining() == 0, "terminal signed speed");
            }
        }
        ByteBuffer b = wire(4, TransmissionNumber.R);
        for (int size = 0; size < b.limit(); size++) {
            ByteBuffer truncated = b.duplicate(); truncated.limit(size);
            check(!PacketGuards.vehicle(truncated, 10), "truncated packet rejected");
        }
        for (int i = 0; i < 1000; i++) {
            Quaternionf rotation = new Quaternionf().rotationXYZ(i * .17f, i * .11f, i * -.31f);
            ByteBuffer rotated = wire(4, TransmissionNumber.Speed8);
            rotated.putFloat(26, rotation.x).putFloat(30, rotation.y).putFloat(34, rotation.z).putFloat(38, rotation.w);
            check(PacketGuards.vehicle(rotated, 10), "real JOML rotations accepted");
            ByteBuffer prefixed = ByteBuffer.allocate(rotated.remaining() + 17); prefixed.position(17);
            prefixed.put(rotated).flip(); prefixed.position(17);
            check(PacketGuards.vehicle(prefixed, 10) && prefixed.position() == 17, "nonzero payload offset accepted");
        }
        System.out.println("PASS: " + checks + " independent real Reader/Writer checks");
        System.out.println("Guard accepts 5 wheels: " + PacketGuards.vehicle(wire(5, TransmissionNumber.N), 10));
        if (corrected) check(!PacketGuards.vehicle(wire(5, TransmissionNumber.N), 10), "five wheels rejected");
        for (float magnitude : new float[]{0, Float.MIN_VALUE, 1e-23f, 1e-20f, 1f, Float.MAX_VALUE}) {
            ByteBuffer poisoned = wire(4, TransmissionNumber.N);
            for (int offset : new int[]{26, 30, 34, 38}) poisoned.putFloat(offset, magnitude);
            Quaternionf q = new Quaternionf(magnitude, magnitude, magnitude, magnitude);
            Transform transform = new Transform(); transform.setRotation(q);
            Quaternionf normalized = new Quaternionf(q).normalize();
            System.out.println("quaternion magnitude=" + magnitude + " guard=" + PacketGuards.vehicle(poisoned, 10)
                + " normSquared=" + q.lengthSquared() + " basisFinite=" + transform.basis.isFinite()
                + " normalizedFinite=" + normalized.isFinite());
            if (corrected) check(PacketGuards.vehicle(poisoned, 10) == (Float.isFinite(q.lengthSquared()) && q.lengthSquared() > 0),
                "float-safe quaternion accepted, unsafe rejected");
        }
        for (int identifier : new int[]{16, 19, 24, 31, 32, 33, 23}) {
            check(!PacketGuards.datagram(ByteBuffer.wrap(new byte[]{(byte)identifier})), "two-byte native header required");
            check(PacketGuards.datagram(ByteBuffer.wrap(new byte[]{(byte)identifier, 0})), "two-byte native header accepted");
        }
        ByteBuffer version = ByteBuffer.allocate(100);
        ByteBufferWriter versionWriter = new ByteBufferWriter(version);
        versionWriter.putByte(25); versionWriter.putUTF("42.21"); version.flip();
        check(PacketGuards.datagram(version), "real UTF version notification accepted");
        System.out.println("one-byte ID25 accepted: " + PacketGuards.datagram(ByteBuffer.wrap(new byte[]{25})));
        if (corrected) {
            for (int size = 1; size < version.limit(); size++) {
                ByteBuffer truncated = version.duplicate(); truncated.limit(size);
                check(!PacketGuards.datagram(truncated), "truncated native UTF version rejected");
            }
            check(!PacketGuards.datagram(ByteBuffer.wrap(new byte[]{25, -1, -1})), "negative UTF length rejected");
            check(PacketGuards.datagram(ByteBuffer.wrap(new byte[]{25, 0, 0})), "empty UTF string accepted");
        }
        for (long time : new long[]{Long.MIN_VALUE, 0, Long.MAX_VALUE}) {
            ByteBuffer timestamp = wire(4, TransmissionNumber.N); timestamp.putLong(2, time);
            System.out.println("timestamp=" + time + " guard=" + PacketGuards.vehicle(timestamp, 10));
        }
        System.out.println("PASS final: " + checks + " assertions; corrected-mode=" + corrected);
    }
}
