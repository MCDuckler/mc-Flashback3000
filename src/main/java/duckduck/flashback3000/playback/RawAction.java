package duckduck.flashback3000.playback;

import net.minecraft.resources.ResourceLocation;

public record RawAction(ResourceLocation type, byte[] payload) {}
