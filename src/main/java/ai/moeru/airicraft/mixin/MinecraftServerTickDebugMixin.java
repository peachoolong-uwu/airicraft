package ai.moeru.airicraft.mixin;

import ai.moeru.airicraft.debug.ServerTickDebugRuntime;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public class MinecraftServerTickDebugMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void airicraft$gateServerTick(BooleanSupplier shouldKeepTicking, CallbackInfo callback) {
		if (!ServerTickDebugRuntime.beginServerTick()) {
			callback.cancel();
		} else {
			ai.moeru.airicraft.os.NativeProgressRuntime.begin((MinecraftServer) (Object) this);
			ai.moeru.airicraft.os.NativeAvailabilityRuntime.begin((MinecraftServer) (Object) this);
		}
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void airicraft$completeServerTick(BooleanSupplier shouldKeepTicking, CallbackInfo callback) {
		ServerTickDebugRuntime.completeServerTick();
		ai.moeru.airicraft.os.NativeProgressRuntime.complete((MinecraftServer) (Object) this);
		ai.moeru.airicraft.os.NativeAvailabilityRuntime.complete((MinecraftServer) (Object) this);
	}
}
