package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;
import ai.moeru.airicraft.debug.ClientTickPlayerActionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {
	@Unique
	private String airicraft$breakingBlockId;

	@Unique
	private BlockPos airicraft$breakingBlockPos;

	@Inject(method = "attackBlock", at = @At("HEAD"), cancellable = true)
	private void airicraft$captureAttackStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (WorldPlacePreservation.blocksPathBreaking(pos)) {
			cir.setReturnValue(false);
			return;
		}
		ClientTickPlayerActionEvents.recordStart("attack");
	}

	@Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
	private void airicraft$captureBlockBreakStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (WorldPlacePreservation.blocksPathBreaking(pos)) {
			cir.setReturnValue(false);
			return;
		}
		ClientPlayerInteractionManagerAccessor accessor = (ClientPlayerInteractionManagerAccessor) (Object) this;
		if (!accessor.airicraft$breakingBlock()) {
			ClientTickPlayerActionEvents.recordStart("attack");
		}
	}

	@Inject(method = "updateBlockBreakingProgress", at = @At("RETURN"))
	private void airicraft$captureBlockBreakProgress(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (!Boolean.TRUE.equals(cir.getReturnValue()) || pos == null) {
			return;
		}
		ClientPlayerInteractionManagerAccessor accessor = (ClientPlayerInteractionManagerAccessor) (Object) this;
		ClientTickPlayerActionEvents.recordBreakProgress(
			pos.getX(),
			pos.getY(),
			pos.getZ(),
			accessor.airicraft$currentBreakingProgress()
		);
	}

	@Inject(method = "attackEntity", at = @At("HEAD"))
	private void airicraft$captureEntityAttackStart(PlayerEntity player, Entity target, CallbackInfo ci) {
		ClientTickPlayerActionEvents.recordStart("attack");
	}

	@Inject(method = "interactBlock", at = @At("HEAD"))
	private void airicraft$captureBlockUseStart(
		ClientPlayerEntity player,
		Hand hand,
		BlockHitResult hitResult,
		CallbackInfoReturnable<ActionResult> cir
	) {
		ClientTickPlayerActionEvents.recordStart("use");
	}

	@Inject(method = "interactItem", at = @At("HEAD"))
	private void airicraft$captureItemUseStart(
		PlayerEntity player,
		Hand hand,
		CallbackInfoReturnable<ActionResult> cir
	) {
		ClientTickPlayerActionEvents.recordStart("use");
	}

	@Inject(method = "breakBlock", at = @At("HEAD"))
	private void airicraft$captureBrokenBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		airicraft$breakingBlockId = null;
		airicraft$breakingBlockPos = null;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || !client.isOnThread() || client.world == null || pos == null) {
			return;
		}

		BlockState state = client.world.getBlockState(pos);
		if (state == null || state.isAir()) {
			return;
		}
		airicraft$breakingBlockId = Registries.BLOCK.getId(state.getBlock()).toString();
		airicraft$breakingBlockPos = pos.toImmutable();
	}

	@Inject(method = "breakBlock", at = @At("RETURN"))
	private void airicraft$reportBrokenBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (!Boolean.TRUE.equals(cir.getReturnValue()) || airicraft$breakingBlockId == null || airicraft$breakingBlockPos == null) {
			return;
		}
		AiricraftClient.runtimeController().onPlayerMinedBlock(
			airicraft$breakingBlockId,
			airicraft$breakingBlockPos.getX(),
			airicraft$breakingBlockPos.getY(),
			airicraft$breakingBlockPos.getZ()
		);
	}
}
