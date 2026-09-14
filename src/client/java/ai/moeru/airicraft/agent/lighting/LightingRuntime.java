package ai.moeru.airicraft.agent.lighting;

import ai.moeru.airicraft.agent.tasks.WorldTaskType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.RaycastContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LightingRuntime {
	private static final long ATTEMPT_INTERVAL_TICKS = 10L;
	private static final long CONFIRMATION_TIMEOUT_TICKS = 20L;
	private static final double MAX_REACH_SQUARED = 4.5D * 4.5D;

	private LightingPolicy policy = LightingPolicy.defaults();
	private PendingPlacement pendingPlacement;
	private long nextAttemptTick;

	public LightingPolicy configure(
		boolean enabled,
		LightingPolicy.Mode mode,
		int maxLightLevel,
		boolean requireUnderground,
		int minSpacingBlocks
	) {
		policy = new LightingPolicy(
			enabled,
			mode,
			maxLightLevel,
			requireUnderground,
			minSpacingBlocks,
			policy.revision() + 1L
		);
		pendingPlacement = null;
		return policy;
	}

	public Optional<PlacementEvent> tick(MinecraftClient client, WorldTaskType activity, long tick) {
		if (client == null || client.world == null || client.player == null || client.interactionManager == null) {
			pendingPlacement = null;
			return Optional.empty();
		}
		Optional<PlacementEvent> confirmation = confirmPending(client, tick);
		if (confirmation.isPresent() || pendingPlacement != null || tick < nextAttemptTick) {
			return confirmation;
		}
		nextAttemptTick = tick + ATTEMPT_INTERVAL_TICKS;

		ClientPlayerEntity player = client.player;
		if (!LightingPolicyEvaluator.supportsActivity(activity) || !policy.enabled()
			|| player.isUsingItem() || client.interactionManager.isBreakingBlock()
			|| player.currentScreenHandler != player.playerScreenHandler
			|| !player.currentScreenHandler.getCursorStack().isEmpty()) return Optional.empty();
		BlockPos origin = player.getBlockPos();
		// Do not interpret an unloaded edge of the sampling area as darkness.
		for (BlockPos sample : BlockPos.iterate(origin.add(-2, 0, -2), origin.add(2, 0, 2))) {
			if (!client.world.isChunkLoaded(sample)) return Optional.empty();
		}
		double combinedLight = averageFootLevelLight(origin, client.world::isAir, pos -> client.world.getLightLevel(pos));
		double blockLight = averageFootLevelLight(origin, client.world::isAir, pos -> client.world.getLightLevel(LightType.BLOCK, pos));
		boolean nearbyTorch = hasNearbyTorch(client, origin, policy.minSpacingBlocks());
		boolean placementRequired = LightingPolicyEvaluator.shouldPlace(
			policy,
			true,
			torchCount(player) > 0,
			client.world.isSkyVisible(origin.up()),
			combinedLight,
			blockLight,
			nearbyTorch
		);
		if (!placementRequired) {
			return Optional.empty();
		}
		for (PlacementCandidate candidate : placementCandidates(player.getBlockPos(), player.getHorizontalFacing())) {
			if (tryPlace(client, player, candidate, activity, tick,
				policy.mode() == LightingPolicy.Mode.SPAWN_PROOF ? blockLight : combinedLight)) {
				break;
			}
		}
		return Optional.empty();
	}

	static double averageFootLevelLight(BlockPos origin, java.util.function.Predicate<BlockPos> isAirAt,
		java.util.function.ToIntFunction<BlockPos> lightAt) {
		int total = 0;
		int samples = 0;
		for (BlockPos sample : BlockPos.iterate(origin.add(-2, 0, -2), origin.add(2, 0, 2))) {
			if (!isAirAt.test(sample)) continue;
			total += lightAt.applyAsInt(sample);
			samples++;
		}
		// No air means no evidence of darkness that should trigger placement.
		return samples == 0 ? Double.POSITIVE_INFINITY : total / (double) samples;
	}

	public void reset() {
		policy = LightingPolicy.defaults();
		pendingPlacement = null;
		nextAttemptTick = 0L;
	}

	public LightingPolicy policy() {
		return policy;
	}

	private Optional<PlacementEvent> confirmPending(MinecraftClient client, long tick) {
		if (pendingPlacement == null) {
			return Optional.empty();
		}
		BlockState state = client.world.getBlockState(pendingPlacement.target());
		if (state.isOf(Blocks.TORCH) || state.isOf(Blocks.WALL_TORCH)) {
			PendingPlacement confirmed = pendingPlacement;
			pendingPlacement = null;
			return Optional.of(new PlacementEvent(Map.of(
				"policyRevision", confirmed.policyRevision(),
				"mode", confirmed.mode().wireName(),
				"x", confirmed.target().getX(),
				"y", confirmed.target().getY(),
				"z", confirmed.target().getZ(),
				"lightLevelBefore", confirmed.lightLevelBefore(),
				"torchCount", torchCount(client.player),
				"side", confirmed.side(),
				"facing", confirmed.face().asString(),
				"activity", confirmed.activity().name().toLowerCase(java.util.Locale.ROOT)
			)));
		}
		if (tick - pendingPlacement.startedTick() > CONFIRMATION_TIMEOUT_TICKS) {
			pendingPlacement = null;
		}
		return Optional.empty();
	}

	private boolean tryPlace(MinecraftClient client, ClientPlayerEntity player, PlacementCandidate candidate, WorldTaskType activity, long tick, double lightBefore) {
		BlockPos target = candidate.target();
		if (!client.world.isChunkLoaded(target)) {
			return false;
		}
		BlockState targetState = client.world.getBlockState(target);
		BlockState torchState = candidate.surface() == PlacementSurface.WALL
			? Blocks.WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, candidate.face())
			: Blocks.TORCH.getDefaultState();
		if (!(targetState.isAir() || targetState.isReplaceable()) || !client.world.getFluidState(target).isEmpty()
			|| !torchState.canPlaceAt(client.world, target)) {
			return false;
		}
		Vec3d hit = Vec3d.ofCenter(candidate.support()).add(
			candidate.face().getOffsetX() * 0.5D,
			candidate.face().getOffsetY() * 0.5D,
			candidate.face().getOffsetZ() * 0.5D
		);
		if (player.getEyePos().squaredDistanceTo(hit) > MAX_REACH_SQUARED) {
			return false;
		}
		// Ray ends just inside the support face, avoiding boundary misses and through-wall clicks.
		Vec3d inside = hit.subtract(Vec3d.of(candidate.face().getVector()).multiply(0.001));
		var visible = client.world.raycast(new RaycastContext(player.getEyePos(), inside,
			RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
		if (visible.getType() != HitResult.Type.BLOCK || !visible.getBlockPos().equals(candidate.support())) return false;
		ActionResult result = placeWithTorch(client, player, new BlockHitResult(hit, candidate.face(), candidate.support(), false));
		if (!result.isAccepted()) {
			return false;
		}
		pendingPlacement = new PendingPlacement(
			target.toImmutable(),
			tick,
			policy.revision(),
			policy.mode(),
			lightBefore,
			candidate.side(),
			candidate.face(),
			activity
		);
		return true;
	}

	static List<PlacementCandidate> placementCandidates(BlockPos origin, Direction forward) {
		Direction left = forward.rotateYCounterclockwise();
		Direction right = forward.rotateYClockwise();
		List<BlockPos> anchors = List.of(
			origin.offset(forward.getOpposite()).up(),
			origin.up(),
			origin.offset(forward).up()
		);
		java.util.ArrayList<PlacementCandidate> candidates = new java.util.ArrayList<>(10);
		addWallCandidates(candidates, anchors, left, "left");
		addWallCandidates(candidates, anchors, right, "right");
		addFloorCandidates(candidates, origin, forward);
		return List.copyOf(candidates);
	}

	private static void addWallCandidates(
		List<PlacementCandidate> candidates,
		List<BlockPos> targets,
		Direction wallDirection,
		String side
	) {
		Direction clickedFace = wallDirection.getOpposite();
		for (BlockPos target : targets) {
			candidates.add(new PlacementCandidate(target, target.offset(wallDirection), clickedFace, side, PlacementSurface.WALL));
		}
	}

	private static void addFloorCandidates(List<PlacementCandidate> candidates, BlockPos origin, Direction forward) {
		for (BlockPos target : List.of(
			origin.offset(forward.getOpposite()),
			origin.offset(forward.rotateYCounterclockwise()),
			origin.offset(forward.rotateYClockwise()),
			origin.offset(forward)
		)) {
			candidates.add(new PlacementCandidate(target, target.down(), Direction.UP, "floor", PlacementSurface.FLOOR));
		}
	}

	private static boolean hasNearbyTorch(MinecraftClient client, BlockPos origin, int radius) {
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int x = -radius; x <= radius; x++) {
			for (int y = -2; y <= 2; y++) {
				for (int z = -radius; z <= radius; z++) {
					cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
					if (!client.world.isChunkLoaded(cursor)) {
						continue;
					}
					BlockState state = client.world.getBlockState(cursor);
					if (state.isOf(Blocks.TORCH) || state.isOf(Blocks.WALL_TORCH)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static int torchCount(ClientPlayerEntity player) {
		return player.getInventory().count(Items.TORCH);
	}

	private static ActionResult placeWithTorch(MinecraftClient client, ClientPlayerEntity player, BlockHitResult hit) {
		if (player.getOffHandStack().isOf(Items.TORCH)) {
			ActionResult result = client.interactionManager.interactBlock(player, Hand.OFF_HAND, hit);
			if (result.isAccepted()) player.swingHand(Hand.OFF_HAND);
			return result;
		}
		ScreenHandler handler = player.currentScreenHandler;
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			if (!handler.getSlot(slot).getStack().isOf(Items.TORCH)) continue;
			int previousSlot = player.getInventory().getSelectedSlot();
			boolean swap = slot < PlayerScreenHandler.HOTBAR_START;
			int torchSlot = swap ? previousSlot : slot - PlayerScreenHandler.HOTBAR_START;
			if (swap) client.interactionManager.clickSlot(handler.syncId, slot, torchSlot, SlotActionType.SWAP, player);
			player.getInventory().setSelectedSlot(torchSlot);
			try {
				ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
				if (result.isAccepted()) player.swingHand(Hand.MAIN_HAND);
				return result;
			}
			finally {
				if (swap) client.interactionManager.clickSlot(handler.syncId, slot, torchSlot, SlotActionType.SWAP, player);
				player.getInventory().setSelectedSlot(previousSlot);
				player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(previousSlot));
			}
		}
		return ActionResult.PASS;
	}

	public record PlacementEvent(Map<String, Object> payload) {
	}

	record PlacementCandidate(BlockPos target, BlockPos support, Direction face, String side, PlacementSurface surface) {
	}

	enum PlacementSurface {
		WALL,
		FLOOR
	}

	private record PendingPlacement(
		BlockPos target,
		long startedTick,
		long policyRevision,
		LightingPolicy.Mode mode,
		double lightLevelBefore,
		String side,
		Direction face,
		WorldTaskType activity
	) {
	}
}
