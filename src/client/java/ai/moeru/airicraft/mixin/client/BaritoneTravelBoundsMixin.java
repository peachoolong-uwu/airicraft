package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.agent.spatial.WorldTravelPolicy;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Moves;
import baritone.utils.pathing.MutableMoveResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Reject whole movement envelopes inside A*, including dynamic falls and parkour. */
@Mixin(value = AStarPathFinder.class, remap = false)
public abstract class BaritoneTravelBoundsMixin {
	@Redirect(method = "calculate0", at = @At(value = "INVOKE", target = "Lbaritone/pathing/movement/Moves;apply(Lbaritone/pathing/movement/CalculationContext;IIILbaritone/utils/pathing/MutableMoveResult;)V"))
	private void airicraft$restrictMovement(Moves move, CalculationContext context, int x, int y, int z, MutableMoveResult result) {
		move.apply(context,x,y,z,result);
		boolean jumping = move.name().startsWith("PARKOUR") || move.name().startsWith("ASCEND") || move == Moves.PILLAR;
		if (!WorldTravelPolicy.permitsMovement(context.world,x,y,z,result.x,result.y,result.z,jumping)) result.cost = Double.POSITIVE_INFINITY;
	}
}
