package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import baritone.Baritone;
import baritone.behavior.Behavior;
import baritone.utils.InputOverrideHandler;
import baritone.api.utils.input.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = InputOverrideHandler.class, remap = false)
public abstract class BaritoneCameraInputMixin extends Behavior {
	protected BaritoneCameraInputMixin(Baritone baritone) { super(baritone); }

	@Inject(method = "isInputForcedDown", at = @At("RETURN"), cancellable = true)
	private void airicraft$waitForAim(Input input, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValue() && ctx.player() != null
			&& !AiricraftClient.runtimeController().cameraController().allowsBaritoneInput(ctx.player(), input)) {
			cir.setReturnValue(false);
		}
	}
}
