package ai.moeru.airicraft.mixin.client;

import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Floors the block-light component of the packed lightmap coordinate so
 * unlit areas stay dimly visible instead of pitch black. Vanilla packs
 * block light into bits 0-3 and sky light into bits 20-23; raising the
 * block-light minimum to 6 gives moonlight-level visibility without
 * affecting sky light, torches, or the day/night cycle.
 */
@Mixin(WorldRenderer.class)
public abstract class LightmapTextureManagerMixin {
	private static final int AIRICRAFT_MOONLIGHT_FLOOR = 6;

	@Inject(method = "getLightmapCoordinates(Lnet/minecraft/client/render/WorldRenderer$BrightnessGetter;Lnet/minecraft/world/BlockRenderView;Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;)I",
		at = @At("RETURN"), cancellable = true)
	private static void airicraft$moonlightFloor(
		WorldRenderer.BrightnessGetter brightnessGetter,
		net.minecraft.world.BlockRenderView world,
		net.minecraft.block.BlockState state,
		net.minecraft.util.math.BlockPos pos,
		CallbackInfoReturnable<Integer> cir
	) {
		int packed = cir.getReturnValue();
		int blockLight = LightmapTextureManager.getBlockLightCoordinates(packed);
		if (blockLight < AIRICRAFT_MOONLIGHT_FLOOR) {
			int skyLight = LightmapTextureManager.getSkyLightCoordinates(packed);
			cir.setReturnValue(LightmapTextureManager.pack(AIRICRAFT_MOONLIGHT_FLOOR, skyLight));
		}
	}
}
