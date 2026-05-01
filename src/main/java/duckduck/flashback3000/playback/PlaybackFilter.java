package duckduck.flashback3000.playback;

import duckduck.flashback3000.protocol.PacketIds;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;

public class PlaybackFilter extends ChannelDuplexHandler {

    public static final String NAME = "flashback3000-playback-filter";

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
            ctx.write(wrapper.packet(), promise);
            return;
        }
        if (this.active) {
            // Allow real-server keep-alive pings so Paper's keep-alive timeout doesn't
            // disconnect the viewer mid-playback. The recorded keep-alives are dropped
            // by PlaybackSession, so the client only echoes ids the real server expects.
            if (msg instanceof ClientboundKeepAlivePacket) {
                ctx.write(msg, promise);
                return;
            }
            // Drop other real-server emissions during playback so they don't fight the trailer.
            promise.setSuccess();
            return;
        }
        ctx.write(msg, promise);
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
