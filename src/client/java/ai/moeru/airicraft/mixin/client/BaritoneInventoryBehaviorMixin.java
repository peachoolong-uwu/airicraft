package ai.moeru.airicraft.mixin.client;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.behavior.Behavior;
import baritone.behavior.InventoryBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Inventory housekeeping must not interrupt eating or other held item use. */
@Mixin(value = InventoryBehavior.class, remap = false)
public abstract class BaritoneInventoryBehaviorMixin extends Behavior {
	protected BaritoneInventoryBehaviorMixin(Baritone baritone) {
		super(baritone);
	}

	@Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
	private void airicraft$preserveActiveItemUse(TickEvent event, CallbackInfo ci) {
		// OUT ticks have no player; let Baritone keep its normal out-of-world handling.
		if (event.getType() != TickEvent.Type.OUT && ctx.player().isUsingItem()) ci.cancel();
	}
}
