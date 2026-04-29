package duckduck.flashback3000.netty;

import duckduck.flashback3000.record.Recorder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;

public class PacketCaptureHandler extends ChannelDuplexHandler {

    public static final String NAME = "flashback3000-capture";

    private final Recorder recorder;

    public PacketCaptureHandler(Recorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        try {
            if (msg instanceof ClientboundBundlePacket bundle) {
                for (Packet<?> sub : bundle.subPackets()) {
                    this.recorder.acceptOutboundPacket(sub);
                }
            } else if (msg instanceof Packet<?> packet) {
                this.recorder.acceptOutboundPacket(packet);
            }
        } catch (Throwable t) {
            this.recorder.onError(t);
        }
        ctx.write(msg, promise);
    }
}
