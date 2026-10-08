package pwshield;

public final class VerifyClasses {
    public static void main(String[] args) throws Exception {
        for (String name : new String[]{"zombie.network.GameServer", "zombie.core.raknet.UdpEngine",
                "zombie.network.ZomboidNetDataPool", "zombie.network.packets.vehicle.VehiclePhysicsPacket",
                "pwshield.ShieldHooks", "pwshield.FairQueue", "pwshield.BoundedPoolQueue"}) {
            Class<?> type = Class.forName(name, false, VerifyClasses.class.getClassLoader());
            type.getDeclaredMethods(); type.getDeclaredConstructors();
        }
        System.out.println("PASS: patched classes load with -Xverify:all (without launching server/native libraries)");
    }
}
