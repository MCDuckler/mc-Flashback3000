package duckduck.flashback3000.netty;

import duckduck.flashback3000.cache.PerPlayerContext;
import duckduck.flashback3000.record.Recorder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;

/**
 * Always-on outbound observer. Updates the per-player {@link PerPlayerContext#cache} for every
 * packet, and forwards live packets to the player's currently active recorder (if any) for the
 * main stream. Bundles are unwrapped so the cache and recorder both see flat individual packets.
 */
public class PacketCaptureHandler extends ChannelDuplexHandler {

    public static final String NAME = "flashback3000-capture";

    private final PerPlayerContext ctx;

    public PacketCaptureHandler(PerPlayerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void write(ChannelHandlerContext c, Object msg, ChannelPromise promise) throws Exception {
        try {
            if (msg instanceof ClientboundBundlePacket bundle) {
                for (Packet<?> sub : bundle.subPackets()) {
                    handle(sub);
                }
            } else if (msg instanceof Packet<?> packet) {
                handle(packet);
            }
        } catch (Throwable t) {
            Recorder rec = ctx.activeRecorder;
            if (rec != null) rec.onError(t);
        }
        c.write(msg, promise);
    }

    private void handle(Packet<?> packet) {
        ctx.cache.update(packet);
        Recorder rec = ctx.activeRecorder;
        if (rec != null) rec.acceptOutboundPacket(packet);
    }
}
