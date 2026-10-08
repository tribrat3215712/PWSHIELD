package pzshield;

/** Implemented by the audited VehiclePhysicsPacket via the offline patcher. */
public interface VehicleStateView {
    boolean pzshieldValidState(int wheelCapacity);
}
