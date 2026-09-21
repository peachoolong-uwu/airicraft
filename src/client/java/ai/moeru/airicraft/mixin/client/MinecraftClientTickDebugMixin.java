package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MinecraftClient.class)
public class MinecraftClientTickDebugMixin {
	// Gate the call itself so Fabric/Baritone tick callbacks cannot run ahead of the pause.
	// Render-loop tasks remain available for bridge reads, stepping and frame capture.
	@WrapWithCondition(method = "render", at = @At(
		value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;tick()V"
	))
	private boolean airicraft$gateClientTick(MinecraftClient client) {
		// During world join the interaction manager exists before the player
		// entity; vanilla skips tick() in that window, so we must too.
		if (client.player == null) {
			return false;
		}
		return AiricraftClient.runtimeController().clientTickDebugRuntime().beginClientTick();
	}

	@ModifyExpressionValue(method = "render", at = @At(
		value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;shouldTick()Z"
	))
	private boolean airicraft$freezeTickInterpolation(boolean shouldTick) {
		return AiricraftClient.runtimeController().clientTickDebugRuntime().allowVanillaTick(shouldTick);
	}
}
