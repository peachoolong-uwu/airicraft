package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.agent.baritone.NavigationDoorInteraction;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = Movement.class, remap = false)
public abstract class BaritoneDoorInteractionMixin {
	@Shadow @Final protected IPlayerContext ctx;
	@Shadow @Final protected BetterBlockPos src;
	@Shadow @Final protected BetterBlockPos dest;

	@Redirect(method = "update", at = @At(value = "INVOKE", target = "Lbaritone/pathing/movement/Movement;updateState(Lbaritone/pathing/movement/MovementState;)Lbaritone/pathing/movement/MovementState;"))
	private MovementState airicraft$handleDoor(Movement movement, MovementState state) {
		return !state.getStatus().isComplete() && NavigationDoorInteraction.update(ctx, src, dest, state)
			? state : movement.updateState(state);
	}
}
