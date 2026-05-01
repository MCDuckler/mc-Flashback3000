package duckduck.flashback3000.playback;

import duckduck.flashback3000.protocol.PacketIds;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PlaybackFilter extends ChannelDuplexHandler {

    public static final String NAME = "flashback3000-playback-filter";
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("Flashback3000-Filter");
    private static final java.util.Set<String> SEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile boolean active = true;
    private static final Map<Integer, EntityType<?>> TRACKED_ENTITY_TYPES = new ConcurrentHashMap<>();

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isActive() {
        return this.active;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // Track entity types as they flow outbound so we can validate later
        // SetEntityData packets against vanilla's expected per-field types.
        recordEntityTracking(msg);
        if (msg instanceof PlaybackPacket wrapper) {
            Object inner = wrapper.packet();
            traceOnce("playback", inner);
            if (inner instanceof BundlePacket<?> || inner instanceof BundleDelimiterPacket<?>) {
                LOG.warning("Refused to dispatch a bundle/delimiter wrapped in PlaybackPacket: "
                        + inner.getClass().getSimpleName());
                promise.setSuccess();
                return;
            }
            ctx.write(inner, promise);
            return;
        }
        if (this.active) {
            traceOnce("real-server", msg);
            if (msg instanceof ClientboundKeepAlivePacket) {
                ctx.write(msg, promise);
                return;
            }
            promise.setSuccess();
            return;
        }
        // Inactive (post-RESTORE) but filter still installed: pass everything through
        // EXCEPT packets the vanilla client can't safely consume from a ModelEngine-
        // augmented server. Without this safety net the player gets kicked the moment
        // ME's tracker emits a bundle for a model entity after scene end.
        if (isDangerousForVanilla(msg)) {
            promise.setSuccess();
            return;
        }
        ctx.write(msg, promise);
    }

    /**
     * SetEntityData (and bundles containing it) with a field id high enough that
     * the vanilla client's per-entity data array overflows. ModelEngine emits
     * custom data slots above any vanilla entity's range.
     */
    private static boolean isDangerousForVanilla(Object msg) {
        if (msg == null) return false;
        Object inner = unwrapProtected(msg);
        if (inner instanceof ClientboundSetEntityDataPacket sed) return isUnsafeSetEntityData(sed);
        if (inner instanceof BundlePacket<?> bp) {
            for (Packet<?> sub : bp.subPackets()) {
                Object subInner = unwrapProtected(sub);
                if (subInner instanceof ClientboundSetEntityDataPacket sed && isUnsafeSetEntityData(sed)) return true;
            }
        }
        return false;
    }

    private static boolean isUnsafeSetEntityData(ClientboundSetEntityDataPacket sed) {
        if (hasHighField(sed)) return true;
        EntityType<?> type = TRACKED_ENTITY_TYPES.get(sed.id());
        if (isDisplayVariant(type) && hasInvalidDisplayFieldType(sed)) return true;
        return false;
    }

    private static boolean isDisplayVariant(EntityType<?> type) {
        return type == EntityType.ITEM_DISPLAY
                || type == EntityType.BLOCK_DISPLAY
                || type == EntityType.TEXT_DISPLAY;
    }

    /**
     * Display variants register strict per-field serializers in vanilla:
     * fields 8 / 9 / 10 are Integer (interpolation timing), 11 / 12 are Vector3,
     * 13 / 14 are Quaternion, 15 is Byte, 16 is Integer, 17-21 are Float, 22 is
     * Integer. ModelEngine emits some of these with a Float serializer where
     * vanilla expects Integer (e.g. field 9 = transformation_interpolation_
     * duration), causing the client's DataTracker to throw IllegalStateException
     * during bundle apply -> "Network Protocol Error" disconnect. Drop those
     * specific malformed packets. The threshold is conservative on the integer-
     * typed fields where we've seen mismatches in the wild.
     */
    private static boolean hasInvalidDisplayFieldType(ClientboundSetEntityDataPacket sed) {
        for (SynchedEntityData.DataValue<?> dv : sed.packedItems()) {
            int id = dv.id();
            if (id == 8 || id == 9 || id == 10 || id == 16 || id == 22) {
                if (dv.serializer() != EntityDataSerializers.INT) return true;
            } else if (id == 15) {
                if (dv.serializer() != EntityDataSerializers.BYTE) return true;
            }
        }
        return false;
    }

    private static void recordEntityTracking(Object msg) {
        Object inner = unwrapProtected(msg);
        if (inner instanceof ClientboundAddEntityPacket add) {
            TRACKED_ENTITY_TYPES.put(add.getId(), add.getType());
        } else if (inner instanceof ClientboundRemoveEntitiesPacket rem) {
            for (int id : rem.getEntityIds()) TRACKED_ENTITY_TYPES.remove(id);
        } else if (inner instanceof BundlePacket<?> bp) {
            for (Packet<?> sub : bp.subPackets()) recordEntityTracking(sub);
        }
    }

    /**
     * ModelEngine wraps certain packets in {@code ProtectedPacket} (a record
     * holding the real Packet under {@code .packet()}). Their pipeline handler
     * {@code ProtectedPacketUnpacker} strips the wrapper before the encoder runs,
     * so the wrapper itself is harmless to vanilla clients - but it hides the
     * inner packet from our safety check. Unwrap reflectively (we don't compile
     * against ModelEngine) so we evaluate dangerousness on the true payload.
     */
    private static Object unwrapProtected(Object msg) {
        if (msg == null) return null;
        Class<?> cls = msg.getClass();
        if (!"com.ticxo.modelengine.api.nms.network.ProtectedPacket".equals(cls.getName())) {
            return msg;
        }
        try {
            return cls.getMethod("packet").invoke(msg);
        } catch (Throwable t) {
            return msg;
        }
    }

    private static boolean hasHighField(ClientboundSetEntityDataPacket sed) {
        for (var dv : sed.packedItems()) {
            // Threshold matches PlaybackSession.hasOutOfBoundsField - chosen to clear
            // every vanilla entity (TextDisplay tops out at ~28 fields) while still
            // catching ModelEngine custom slots > 30.
            if (dv.id() > 24) return true;
        }
        return false;
    }

    private static void traceOnce(String origin, Object msg) {
        String name = msg == null ? "null" : msg.getClass().getName();
        if (SEEN.add(origin + ":" + name)) {
            LOG.info("PlaybackFilter saw " + origin + " write of " + name);
        }
    }

    public static void resetTrace() {
        SEEN.clear();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (this.active) {
            // Allow keep-alive / pong so the connection stays open. Drop everything else
            // except plugin messages on our control channel — those carry mid-playback
            // commands like CANCEL_PLAYBACK that must reach the server.
            if (msg instanceof ServerboundKeepAlivePacket || msg instanceof ServerboundPongPacket) {
                ctx.fireChannelRead(msg);
                return;
            }
            if (msg instanceof ServerboundCustomPayloadPacket pkt) {
                var id = pkt.payload().type().id();
                if (id.getNamespace().equals(PacketIds.CHANNEL_NAMESPACE)
                        && id.getPath().equals("control")) {
                    ctx.fireChannelRead(msg);
                    return;
                }
            }
            return;
        }
        ctx.fireChannelRead(msg);
    }
}
