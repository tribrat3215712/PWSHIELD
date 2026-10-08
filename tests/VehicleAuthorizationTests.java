package pzshield;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import sun.misc.Unsafe;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.fields.vehicle.VehicleID;
import zombie.network.packets.vehicle.VehiclePhysicsPacket;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.TransmissionNumber;
import zombie.vehicles.VehicleParts;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.VehicleEngine;
import zombie.vehicles.VehicleManager;
import zombie.core.physics.CarController;
import zombie.core.random.RandStandard;
import zombie.core.random.RandLua;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import zombie.core.physics.Transform;
import pzshield.ShieldHooks;

/** Real ownership and packet processing; Unsafe skips server/world/physics construction. */
public final class VehicleAuthorizationTests {
    static int checks;
    static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
    static Object unconstructed(Unsafe unsafe, Class<?> type) throws Exception {
        return unsafe.allocateInstance(type);
    }
    public static final class Packet extends VehiclePhysicsPacket {
        int forwards;
        void configure(BaseVehicle vehicle, short wheelCount) {
            vehicleId.set(vehicle);
            x = 10; y = 20; z = 0; qx = qy = qz = 0; qw = 1;
            force = 123; vx = 1; vy = 2; vz = 3; engineSpeed = 777; throttle = .25f;
            transmissionNumber = TransmissionNumber.Speed1;
            setNumWheels(wheelCount);
            for (int i = 0; i < wheelCount; i++) {
                wheelSteering[i] = .1f; wheelRotation[i] = .2f;
                wheelSkidInfo[i] = .3f; wheelSuspensionLength[i] = .4f;
            }
        }
        void quaternion(float x, float y, float z, float w) { qx = x; qy = y; qz = z; qw = w; }
        @Override public void sendToRelativeClients(PacketTypes.PacketType type, UdpConnection excluded, float x, float y) {
            forwards++;
        }
    }
    static void setFinal(Object target, String fieldName, Object value) throws Exception {
        Field field = BaseVehicle.class.getDeclaredField(fieldName); field.setAccessible(true); field.set(target, value);
    }
    static void setDeclared(Object target, Class<?> type, String fieldName, Object value) throws Exception {
        Field field = type.getDeclaredField(fieldName); field.setAccessible(true); field.set(target, value);
    }
    static ByteBufferReader physicsWire(short id, int wheels, boolean zeroQuaternion, float speed) {
        ByteBuffer b = ByteBuffer.allocate(69 + wheels * 16);
        ByteBufferWriter writer = new ByteBufferWriter(b);
        writer.putShort(id); writer.putLong(123456789L);
        for (float value : new float[]{123, 10, 20, 0, 0, 0, 0, zeroQuaternion ? 0 : 1, 1, 2, 3, 777, .25f}) {
            writer.putFloat(value);
        }
        writer.putEnum(TransmissionNumber.Speed1); writer.putShort((short)wheels);
        for (int i = 0; i < wheels; i++) {
            writer.putFloat(.1f); writer.putFloat(.2f); writer.putFloat(.3f); writer.putFloat(.4f);
        }
        writer.putFloat(speed); b.flip(); return new ByteBufferReader(b);
    }
    public static void main(String[] args) throws Exception {
        RandStandard.INSTANCE.init(); RandLua.INSTANCE.init();
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        Unsafe unsafe = (Unsafe) singleton.get(null);
        GameServer.server = true;
        BaseVehicle vehicle = (BaseVehicle) unconstructed(unsafe, BaseVehicle.class);
        vehicle.vehicleId = 5;
        vehicle.netPlayerId = 12;
        IsoPlayer owner = (IsoPlayer) unconstructed(unsafe, IsoPlayer.class); owner.onlineId = 12;
        IsoPlayer outsider = (IsoPlayer) unconstructed(unsafe, IsoPlayer.class); outsider.onlineId = 13;
        UdpConnection connection = (UdpConnection) unconstructed(unsafe, UdpConnection.class);
        Field players = UdpConnection.class.getDeclaredField("players");
        players.setAccessible(true); players.set(connection, new IsoPlayer[]{null, owner, outsider, null});
        Field wheels = BaseVehicle.class.getDeclaredField("wheelInfo");
        wheels.setAccessible(true); wheels.set(vehicle, new BaseVehicle.WheelInfo[4]);
        Packet packet = new Packet(); packet.configure(vehicle, (short)4);
        Field id = VehiclePhysicsPacket.class.getDeclaredField("vehicleId"); id.setAccessible(true);
        ((VehicleID) id.get(packet)).set(vehicle);
        for (BaseVehicle.Authorization auth : BaseVehicle.Authorization.values()) {
            vehicle.netPlayerAuthorization = auth;
            check(vehicle.hasAuthorization(connection), "vanilla authorized split-screen owner in " + auth);
            check(ShieldHooks.vehicleOwner(packet, connection), "hook preserves vanilla ownership in " + auth);
        }
        check(!vehicle.hasAuthorization(null) && !ShieldHooks.vehicleOwner(packet, null), "null connection rejected");
        vehicle.netPlayerId = 12;
        vehicle.setSpeedKmHour(1);
        ShieldHooks.setVehicleSpeed(vehicle, -23.5f, connection, packet);
        check(vehicle.getCurrentSpeedKmHour() == -23.5f, "owner signed speed accepted");
        ShieldHooks.setVehicleSpeed(vehicle, Float.NaN, connection, packet);
        check(vehicle.getCurrentSpeedKmHour() == -23.5f, "nonfinite speed rejected before mutation");
        vehicle.netPlayerId = 44;
        check(!vehicle.hasAuthorization(connection) && !ShieldHooks.vehicleOwner(packet, connection), "old owner rejected after handoff");
        ShieldHooks.setVehicleSpeed(vehicle, 100, connection, packet);
        check(vehicle.getCurrentSpeedKmHour() == -23.5f, "old owner cannot mutate speed");
        vehicle.netPlayerId = -1;
        check(!vehicle.hasAuthorization(connection) && !ShieldHooks.vehicleOwner(packet, connection), "server-owned vehicle rejected");
        ShieldHooks.setVehicleSpeed(vehicle, 120, connection, packet);
        check(vehicle.getCurrentSpeedKmHour() == -23.5f, "server-owned vehicle speed unchanged");
        ((VehicleID) id.get(packet)).set(null);
        check(!ShieldHooks.vehicleOwner(packet, connection), "missing vehicle rejected");

        BaseVehicle processVehicle = (BaseVehicle) unconstructed(unsafe, BaseVehicle.class);
        processVehicle.vehicleId = 6; processVehicle.netPlayerId = 12;
        CarController controller = (CarController) unconstructed(unsafe, CarController.class);
        setFinal(processVehicle, "physics", controller);
        VehicleParts parts = new VehicleParts();
        VehiclePart enginePart = (VehiclePart) unconstructed(unsafe, VehiclePart.class);
        VehicleEngine engine = (VehicleEngine) unconstructed(unsafe, VehicleEngine.class);
        setDeclared(enginePart, VehiclePart.class, "engine", engine);
        setDeclared(parts, VehicleParts.class, "engine", enginePart);
        setFinal(processVehicle, "parts", parts);
        setFinal(processVehicle, "savedRot", new Quaternionf());
        setFinal(processVehicle, "jniTransform", new Transform());
        setFinal(processVehicle, "jniLinearVelocity", new Vector3f());
        BaseVehicle.WheelInfo[] wheelInfo = new BaseVehicle.WheelInfo[4];
        for (int i = 0; i < wheelInfo.length; i++) wheelInfo[i] = new BaseVehicle.WheelInfo();
        setFinal(processVehicle, "wheelInfo", wheelInfo);
        Packet processPacket = new Packet(); processPacket.configure(processVehicle, (short)4);
        processPacket.processServer(null, connection);
        check(processVehicle.getX() == 10 && processVehicle.getY() == 20 && controller.clientForce == 123,
            "real patched processServer mutates authorized physics");
        check(engine.getSpeed() == 777 && processVehicle.wheelInfo[3].suspensionLength == .4f,
            "real patched processServer applies all four wheels and engine speed");
        check(processPacket.forwards == 1, "authorized physics forwarded");
        processVehicle.netPlayerId = 44;
        processVehicle.setX(1234); processPacket.forwards = 0;
        processPacket.processServer(null, connection);
        check(processVehicle.getX() == 1234 && processPacket.forwards == 0,
            "real patched processServer drops stale owner before mutation and forwarding");
        processPacket.configure(null, (short)4);
        processPacket.processServer(null, connection);
        check(processPacket.forwards == 0, "real patched processServer safely drops removed vehicle");
        processVehicle.netPlayerId = 12; processVehicle.setX(1234);
        processPacket.configure(processVehicle, (short)5);
        boolean boundsFailure = false;
        try { processPacket.processServer(null, connection); }
        catch (ArrayIndexOutOfBoundsException expected) { boundsFailure = true; }
        System.out.println("five-wheel processServer: boundsFailure=" + boundsFailure
            + " xMutated=" + (processVehicle.getX() != 1234) + " forwarded=" + processPacket.forwards);
        check(!boundsFailure && processVehicle.getX() == 1234 && processPacket.forwards == 0,
            "corrected processServer rejects five wheels before mutations");
        for (float magnitude : new float[]{0, Float.MIN_VALUE, 1e-23f, Float.MAX_VALUE}) {
            processPacket.configure(processVehicle, (short)4);
            processPacket.quaternion(magnitude, magnitude, magnitude, magnitude);
            check(!ShieldHooks.vehicleOwner(processPacket, connection), "main-thread unsafe quaternion rejected");
            processPacket.processServer(null, connection);
            check(processVehicle.getX() == 1234 && processPacket.forwards == 0,
                "unsafe quaternion cannot mutate or forward physics");
            vehicle.setSpeedKmHour(-23.5f); vehicle.netPlayerId = 12;
            packet.configure(vehicle, (short)4); packet.quaternion(magnitude, magnitude, magnitude, magnitude);
            ShieldHooks.setVehicleSpeed(vehicle, 100, connection, packet);
            check(vehicle.getCurrentSpeedKmHour() == -23.5f, "unsafe quaternion cannot mutate speed");
        }
        VehicleManager.instance = new VehicleManager();
        VehicleManager.instance.registerVehicle(vehicle);
        vehicle.netPlayerId = 12;
        packet.parse(physicsWire(vehicle.vehicleId, 4, false, -40), connection);
        check(vehicle.getCurrentSpeedKmHour() == -40, "real patched parse accepts authorized signed speed");
        vehicle.netPlayerId = 44;
        packet.parse(physicsWire(vehicle.vehicleId, 4, false, 44), connection);
        check(vehicle.getCurrentSpeedKmHour() == -40, "real patched parse rejects stale owner's speed");
        vehicle.netPlayerId = 12;
        packet.parse(physicsWire(vehicle.vehicleId, 5, false, 55), connection);
        check(vehicle.getCurrentSpeedKmHour() == -40, "real patched parse rejects five-wheel speed before processServer");
        packet.parse(physicsWire(vehicle.vehicleId, 4, true, 66), connection);
        check(vehicle.getCurrentSpeedKmHour() == -40, "real patched parse rejects zero-quaternion speed");
        packet.parse(physicsWire(vehicle.vehicleId, 4, false, Float.NaN), connection);
        check(vehicle.getCurrentSpeedKmHour() == -40, "real patched parse rejects NaN speed");
        packet.parse(physicsWire((short)77, 4, false, 77), connection);
        check(vehicle.getCurrentSpeedKmHour() == -40, "real patched parse safely handles unknown vehicle");
        System.out.println("PASS: " + checks + " real BaseVehicle ownership/speed-hook checks; no RakNet/Bullet/server startup");
    }
}
