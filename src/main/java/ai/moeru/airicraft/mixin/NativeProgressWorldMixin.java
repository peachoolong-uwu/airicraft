package ai.moeru.airicraft.mixin;

import ai.moeru.airicraft.os.NativeProgressRuntime;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerWorld.class)
public class NativeProgressWorldMixin {
	@Inject(method = "tickChunk", at = @At("TAIL"))
	private void airicraft$randomTickScope(WorldChunk chunk, int randomTickSpeed, CallbackInfo callback) {
		if (randomTickSpeed > 0) NativeProgressRuntime.chunk((ServerWorld) (Object) this, chunk.getPos().x, chunk.getPos().z);
	}
}
