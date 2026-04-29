package duckduck.flashback3000.playback;

import net.minecraft.network.protocol.Packet;

/** Marker wrapper. PlaybackFilter unwraps before forwarding so the encoder sees a plain Packet. */
public record PlaybackPacket(Packet<?> packet) {}
