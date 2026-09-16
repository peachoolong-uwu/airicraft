package ai.moeru.airicraft.mixin;

import ai.moeru.airicraft.os.NativeProgressRuntime;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PassiveEntity.class)
public class NativeProgressEntityMixin {
	@Inject(method = "tickMovement", at = @At("TAIL"))
	private void airicraft$ageProgress(CallbackInfo callback) {
		var entity = (PassiveEntity) (Object) this;
		if (entity.isAlive() && entity.getWorld() instanceof ServerWorld world) NativeProgressRuntime.entity(world, entity.getUuid());
	}
}
