package duckduck.flashback3000.cache;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Per-player accumulator for entity state derived from the server→client packet stream.
 *
 * <p>Mirrors what Flashback's stock client-side recorder does in {@code Recorder.writeSnapshot}:
 * client maintains every entity it's ever been told about as a real NMS entity, then iterates
 * {@code level.entities()} and synthesizes {@code AddEntity + SetEntityData + SetPassengers}
 * from current state. We can't iterate a server-side level for ME's fake AEC/ITEM_DISPLAY/etc.
 * (those are packet-only — never registered as NMS entities), so we observe outgoing packets
 * and accumulate the same state in this cache.
 *
 * <p>Updated by {@link duckduck.flashback3000.netty.PacketCaptureHandler} on every outbound
 * packet (bundles unwrapped first). At snapshot time, {@link #writeSnapshot} emits an
 * {@code AddEntity} per cached entity with its latest position/rotation/movement, plus the
 * merged data values, and the most recent passenger / equipment / attribute packets.
 */
public final class EntityStateCache {

    /**
     * Per-entity state. All fields are mutated only by {@link #update}, which is called from
     * the netty event loop, so writes are single-threaded; reads from {@link #writeSnapshot}
     * happen on the Bukkit main thread, wrapped in a synchronized block.
     */
    public static final class State {
        public UUID uuid;
        public EntityType<?> type;
        public int data;
        public double x, y, z;
        public float xRot, yRot, yHeadRot;
        public Vec3 deltaMovement = Vec3.ZERO;
        public boolean onGround;

        // Latest value per accessor id. LinkedHashMap preserves insertion order so re-emitted
        // packets serialize fields in the same order they originally arrived (cosmetic).
        public final Map<Integer, SynchedEntityData.DataValue<?>> mergedData = new LinkedHashMap<>();

        public @Nullable ClientboundSetPassengersPacket passengers;
        public @Nullable ClientboundUpdateAttributesPacket attributes;
        public @Nullable ClientboundSetEquipmentPacket equipment;
        public @Nullable ClientboundSetEntityLinkPacket leash;
    }

    private final Map<Integer, State> entities = new ConcurrentHashMap<>();

    public boolean isEmpty() { return entities.isEmpty(); }

    public int size() { return entities.size(); }

    /** Update the cache from one outbound packet. Call once per packet — including bundle subpackets. */
    public void update(Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) update(sub);
            return;
        }
        if (packet instanceof ClientboundAddEntityPacket add) {
            State s = entities.computeIfAbsent(add.getId(), k -> new State());
            synchronized (s) {
                s.uuid = add.getUUID();
                s.type = add.getType();
                s.data = add.getData();
                s.x = add.getX();
                s.y = add.getY();
                s.z = add.getZ();
                s.xRot = add.getXRot();
                s.yRot = add.getYRot();
                s.yHeadRot = add.getYHeadRot();
                s.deltaMovement = add.getMovement();
            }
            return;
        }
        if (packet instanceof ClientboundRemoveEntitiesPacket rem) {
            rem.getEntityIds().forEach((java.util.function.IntConsumer) entities::remove);
            return;
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
            State s = entities.get(sync.id());
            if (s == null) return;
            synchronized (s) {
                PositionMoveRotation v = sync.values();
                s.x = v.position().x;
                s.y = v.position().y;
                s.z = v.position().z;
                s.deltaMovement = v.deltaMovement();
                s.yRot = v.yRot();
                s.xRot = v.xRot();
                s.onGround = sync.onGround();
            }
            return;
        }
        if (packet instanceof ClientboundTeleportEntityPacket tp) {
            State s = entities.get(tp.id());
            if (s == null) return;
            synchronized (s) {
                // Fields are absolute or delta depending on Set<Relative>. For simplicity we
                // treat all as absolute here — we lose precision for relative teleports, but
                // these are rare enough that the snapshot will be close. ActionMoveEntities
                // corrects vanilla entity positions on the next playback tick anyway.
                Set<Relative> rel = tp.relatives();
                PositionMoveRotation v = tp.change();
                if (rel.isEmpty()) {
                    s.x = v.position().x;
                    s.y = v.position().y;
                    s.z = v.position().z;
                    s.deltaMovement = v.deltaMovement();
                    s.yRot = v.yRot();
                    s.xRot = v.xRot();
                } else {
                    PositionMoveRotation current = new PositionMoveRotation(
                            new Vec3(s.x, s.y, s.z), s.deltaMovement, s.yRot, s.xRot);
                    PositionMoveRotation absolute = PositionMoveRotation.calculateAbsolute(current, v, rel);
                    s.x = absolute.position().x;
                    s.y = absolute.position().y;
                    s.z = absolute.position().z;
                    s.deltaMovement = absolute.deltaMovement();
                    s.yRot = absolute.yRot();
                    s.xRot = absolute.xRot();
                }
                s.onGround = tp.onGround();
            }
            return;
        }
        if (packet instanceof ClientboundSetEntityDataPacket data) {
            State s = entities.get(data.id());
            if (s == null) return;
            synchronized (s) {
                for (SynchedEntityData.DataValue<?> dv : data.packedItems()) {
                    s.mergedData.put(dv.id(), dv);
                }
            }
            return;
        }
        if (packet instanceof ClientboundSetPassengersPacket sp) {
            State s = entities.get(sp.getVehicle());
            if (s == null) return;
            synchronized (s) { s.passengers = sp; }
            return;
        }
        if (packet instanceof ClientboundUpdateAttributesPacket attrs) {
            State s = entities.get(attrs.getEntityId());
            if (s == null) return;
            synchronized (s) { s.attributes = attrs; }
            return;
        }
        if (packet instanceof ClientboundSetEquipmentPacket equip) {
            State s = entities.get(equip.getEntity());
            if (s == null) return;
            synchronized (s) { s.equipment = equip; }
            return;
        }
        if (packet instanceof ClientboundSetEntityLinkPacket link) {
            // Source id is private — we don't have a public getter. Skip cache, but still
            // pass through; recorder forwards the live packet to the main stream.
            return;
        }
        // ClientboundMoveEntityPacket {Pos, PosRot, Rot} and ClientboundRotateHeadPacket
        // have private/protected entityId fields. F3K's IgnoredPackets filters these out of
        // the recording; for the cache, vanilla entity positions get corrected by the
        // synthesized ActionMoveEntities each playback tick, and ME's fake pivots use
        // ClientboundEntityPositionSyncPacket (handled above), so dropping them is fine.
    }

    /**
     * Walk every cached entity and emit packets that recreate its current client-side state.
     *
     * @param recordingPlayerId  skip emitting AddEntity for the local player; their entity is
     *                           rebuilt by {@code ActionCreateLocalPlayer}.
     */
    public void writeSnapshot(int recordingPlayerId, Consumer<Packet<? super ClientGamePacketListener>> out) {
        // Two passes so SetPassengers always lands AFTER all AddEntity for cached entities.
        // Within Flashback playback the SetPassengers handler triggers a flushPendingEntities
        // before resolving the vehicle id, so order across single-pass would also work — but
        // emitting all spawn packets first matches Flashback's own client-side recorder layout
        // and avoids surprises for any consumer that processes packets in stream order.
        List<Map.Entry<Integer, State>> snapshot = new ArrayList<>(entities.entrySet());

        // Snapshot current pos + passenger lists into local maps without holding multiple
        // State locks at once. Used below to anchor each entity at its root vehicle's
        // current position rather than its own stale AddEntity position.
        record Pos(double x, double y, double z) {}
        Map<Integer, Pos> currentPos = new java.util.HashMap<>(snapshot.size());
        Map<Integer, Integer> passengerToVehicle = new java.util.HashMap<>();
        for (Map.Entry<Integer, State> entry : snapshot) {
            int id = entry.getKey();
            State s = entry.getValue();
            synchronized (s) {
                currentPos.put(id, new Pos(s.x, s.y, s.z));
                if (s.passengers != null) {
                    int[] pids = s.passengers.getPassengers();
                    int vehicle = s.passengers.getVehicle();
                    for (int pid : pids) passengerToVehicle.put(pid, vehicle);
                }
            }
        }

        for (Map.Entry<Integer, State> entry : snapshot) {
            int id = entry.getKey();
            if (id == recordingPlayerId) continue;
            State s = entry.getValue();
            synchronized (s) {
                if (s.uuid == null || s.type == null) continue; // never saw AddEntity

                // Walk passenger chain to root vehicle, anchor at root's current pos. ME
                // bones (ITEM_DISPLAY) are passengers whose Display.tick override skips
                // super.tick → rideTick → positionRider, so the client renders from the
                // anchor set at AddEntity time forever. Native Flashback's client recorder
                // reads the bone's CURRENT pos (which client-side positionRider has been
                // updating to vehicle pos every tick) — we mimic by resolving the chain.
                double x = s.x, y = s.y, z = s.z;
                Integer vehicleId = passengerToVehicle.get(id);
                int hops = 0;
                while (vehicleId != null && hops++ < 16) {
                    Pos vp = currentPos.get(vehicleId);
                    if (vp == null) break;
                    x = vp.x; y = vp.y; z = vp.z;
                    vehicleId = passengerToVehicle.get(vehicleId);
                }

                out.accept(new ClientboundAddEntityPacket(
                        id, s.uuid,
                        x, y, z,
                        s.xRot, s.yRot,
                        s.type, s.data, s.deltaMovement, s.yHeadRot));

                if (!s.mergedData.isEmpty()) {
                    out.accept(new ClientboundSetEntityDataPacket(id, new ArrayList<>(s.mergedData.values())));
                }
                if (s.attributes != null) out.accept(s.attributes);
                if (s.equipment != null) out.accept(s.equipment);
                if (s.leash != null) out.accept(s.leash);
            }
        }

        for (Map.Entry<Integer, State> entry : snapshot) {
            int id = entry.getKey();
            if (id == recordingPlayerId) continue;
            State s = entry.getValue();
            synchronized (s) {
                if (s.passengers != null) out.accept(s.passengers);
            }
        }
    }
}
