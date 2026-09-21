package ai.moeru.airicraft.mixin.client;

import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.process.MineProcess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Identifies resource targets separately from blocks cleared along the mining route. */
@Mixin(value = MineProcess.class, remap = false)
public interface BaritoneMineProcessAccessor {
	@Accessor("filter")
	BlockOptionalMetaLookup airicraft$miningFilter();
}
