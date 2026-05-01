package duckduck.flashback3000.playback;

import duckduck.flashback3000.protocol.PacketIds;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;

public class PlaybackFilter extends ChannelDuplexHandler {

    public static final String NAME = "flashback3000-playback-filter";
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("Flashback3000-Filter");
    private static final java.util.Set<String> SEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile boolean active = true;

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isActive() {
        return this.active;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
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
        ctx.write(msg, promise);
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
