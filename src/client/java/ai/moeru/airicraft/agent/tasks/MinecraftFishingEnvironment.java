package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.mixin.client.FishingBobberEntityAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

final class MinecraftFishingEnvironment implements FishingTaskExecutor.Environment {
	private final CameraController camera;
	MinecraftFishingEnvironment(CameraController camera) { this.camera = camera; }
	private MinecraftClient client() { return MinecraftClient.getInstance(); }

	@Override public String validate(FishOnceStepArgs args) {
		var client = client();
		if (client.world == null || client.player == null || client.interactionManager == null) return "world_unavailable";
		if (hookId() != null) return "fishing_hook_already_present";
		if (!ready()) return "fishing_requires_held_rod_and_closed_container";
		var water = new BlockPos(args.x(), args.y(), args.z());
		if (!client.world.isChunkLoaded(water)) return "fishing_target_unloaded";
		if (!client.world.getFluidState(water).isIn(FluidTags.WATER)) return "fishing_target_not_water";
		if (client.player.squaredDistanceTo(Vec3d.ofCenter(water)) > 12 * 12) return "fishing_target_too_far";
		if (!client.player.isOnGround() || client.player.isTouchingWater()) return "fishing_requires_dry_standing_position";
		return null;
	}

	private boolean ready() {
		var client = client();
		return client.player != null && client.interactionManager != null
			&& client.player.getMainHandStack().isOf(Items.FISHING_ROD)
			&& client.player.currentScreenHandler == client.player.playerScreenHandler
			&& client.player.currentScreenHandler.getCursorStack().isEmpty();
	}
	@Override public UUID hookId() {
		var player = client().player;
		return player == null || player.fishHook == null ? null : player.fishHook.getUuid();
	}
	@Override public boolean biting() {
		return hookId() != null && ((FishingBobberEntityAccessor) client().player.fishHook).airicraft$hasBite();
	}
	@Override public boolean inWater() {
		if (hookId() == null || client().world == null) return false;
		var hook = client().player.fishHook;
		// Bobbers can float in water while Entity.isTouchingWater() remains false.
		// Sample just below the float so its small surface oscillation still counts.
		var floatPosition = BlockPos.ofFloored(hook.getX(), hook.getY() - 0.25, hook.getZ());
		return client().world.getFluidState(floatPosition).isIn(FluidTags.WATER);
	}
	@Override public boolean hookedEntity() { return hookId() != null && client().player.fishHook.getHookedEntity() != null; }
	@Override public boolean cast(FishOnceStepArgs args) {
		if (!ready() || hookId() != null) return false;
		var client = client();
		camera.lookAtNow(client, new Vec3d(args.x() + 0.5, args.y() + 0.8, args.z() + 0.5));
		return client.interactionManager.interactItem(client.player, Hand.MAIN_HAND).isAccepted();
	}
	@Override public void reel(UUID ownedHook) {
		if (ready() && ownedHook.equals(hookId())) client().interactionManager.interactItem(client().player, Hand.MAIN_HAND);
	}
}
