package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.agent.spatial.WorldTravelPolicy;
import baritone.pathing.movement.Movement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.BetterBlockPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Recheck cached paths after policy changes before executing a movement. */
@Mixin(value = Movement.class, remap = false)
public abstract class BaritoneTravelExecutionMixin {
	@Shadow @Final protected IPlayerContext ctx;
	@Shadow @Final protected BetterBlockPos src;
	@Shadow @Final protected BetterBlockPos dest;
	@Inject(method = "update", at = @At("HEAD"), cancellable = true)
	private void airicraft$checkCurrentMovement(CallbackInfoReturnable<MovementStatus> cir) {
		String type = getClass().getSimpleName();
		boolean jumping = type.contains("Parkour") || type.contains("Ascend") || type.contains("Pillar");
		if (!WorldTravelPolicy.permitsMovement(ctx.world(),src.getX(),src.getY(),src.getZ(),dest.getX(),dest.getY(),dest.getZ(),jumping))
			cir.setReturnValue(MovementStatus.UNREACHABLE);
	}
}
