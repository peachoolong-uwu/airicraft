package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import baritone.Baritone;
import baritone.behavior.Behavior;
import baritone.behavior.LookBehavior;
import baritone.api.utils.Rotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep Baritone's target out of its independent rotation writer. */
@Mixin(value = LookBehavior.class, remap = false)
public abstract class BaritoneLookBehaviorMixin extends Behavior {
	protected BaritoneLookBehaviorMixin(Baritone baritone) { super(baritone); }

	@Inject(method = "updateTarget", at = @At("HEAD"), cancellable = true)
	private void airicraft$requestCamera(Rotation rotation, boolean force, CallbackInfo ci) {
		AiricraftClient.runtimeController().cameraController()
			.lookFromBaritone(ctx.player(), rotation.getYaw(), rotation.getPitch());
		ci.cancel();
	}
}
