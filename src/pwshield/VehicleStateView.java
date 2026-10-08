package pwshield;

/** Implemented by the audited VehiclePhysicsPacket via the offline patcher. */
public interface VehicleStateView {
    boolean pwshieldValidState(int wheelCapacity);
}
