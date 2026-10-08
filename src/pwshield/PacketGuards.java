package pwshield;

import java.nio.ByteBuffer;

public final class PacketGuards {
    private PacketGuards() {}
    public static boolean datagram(ByteBuffer buffer) {
        int size = buffer.remaining();
        if (size == 0 || size > 1_000_000) return false;
        int identifier = Byte.toUnsignedInt(buffer.get(buffer.position()));
        return switch (identifier) {
            case 134 -> size >= 3;
            case 16, 19, 24, 31, 32, 33, 23 -> size >= 2;
            case 25 -> size >= 3 && buffer.getShort(buffer.position() + 1) >= 0 &&
                buffer.getShort(buffer.position() + 1) <= size - 3;
            default -> true;
        };
    }
    /** Layout confirmed against this JAR's VehiclePhysicsPacket.parse and IDShort. */
    public static boolean vehicle(ByteBuffer buffer, int transmissionCount) {
        int start = buffer.position(), size = buffer.remaining();
        // IDShort + timestamp + 13 floats + enum byte + wheel count + speed.
        if (size < 69) return false;
        for (int i = 0; i < 13; ++i)
            if (!Float.isFinite(buffer.getFloat(start + 10 + i * 4))) return false;
        if (Byte.toUnsignedInt(buffer.get(start + 62)) >= transmissionCount) return false;
        int wheels = buffer.getShort(start + 63);
        // BaseVehicle.MAX_WHEELS and wheelInfo.length are exactly four in the pinned JAR.
        if (wheels < 0 || wheels > 4 || wheels > (size - 69) / 16) return false;
        if (!quaternion(buffer.getFloat(start + 26), buffer.getFloat(start + 30),
                buffer.getFloat(start + 34), buffer.getFloat(start + 38))) return false;
        for (int i = 0; i < wheels * 4; ++i)
            if (!Float.isFinite(buffer.getFloat(start + 65 + i * 4))) return false;
        return Float.isFinite(buffer.getFloat(start + 65 + wheels * 16));
    }

    public static boolean quaternion(float x, float y, float z, float w) {
        float squared = x * x + y * y + z * z + w * w;
        return Float.isFinite(squared) && squared > 0;
    }
}
