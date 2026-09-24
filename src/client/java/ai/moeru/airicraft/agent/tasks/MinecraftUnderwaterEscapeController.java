package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minecraft adapter shared by underwater harvesting and drowning reflexes.
 * One recovery generation memoizes only incrementally inspected world cells
 * and never retries a failed target, even across recovery-mode changes.
 */
public final class MinecraftUnderwaterEscapeController {
	private final MovementController movement;
	private final CameraController camera;
	private final BaritoneFacade baritone;
	private final UnderwaterEscapeNavigator navigator;
	private MinecraftClient activeClient;

	private UnderwaterEscapeSearch.SearchMode mode;
	private UnderwaterEscapeSearch.SearchSession searchSession;
	private List<UnderwaterEscapeSearch.Candidate> candidates = List.of();
	private UnderwaterEscapeSearch.SearchStatus searchStatus = UnderwaterEscapeSearch.SearchStatus.SEARCHING;
	private boolean waitingForBaritoneRelease;

	public MinecraftUnderwaterEscapeController(
		BaritoneFacade baritone,
		MovementController movement,
		CameraController camera
	) {
		this.baritone = baritone;
		this.movement = Objects.requireNonNull(movement, "movement");
		this.camera = Objects.requireNonNull(camera, "camera");
		this.navigator = new UnderwaterEscapeNavigator(baritone, new UnderwaterEscapeNavigator.WaypointDriver() {
			@Override
			public void moveToward(UnderwaterEscapeSearch.Position waypoint, long tick) {
				MinecraftClient client = activeClient;
				ClientPlayerEntity player = client == null ? null : client.player;
				if (player == null) {
					MinecraftUnderwaterEscapeController.this.movement.stop(client);
					return;
				}
				Vec3d target = new Vec3d(waypoint.x() + 0.5D, waypoint.y() + 1.0D, waypoint.z() + 0.5D);
				MinecraftUnderwaterEscapeController.this.camera.lookAt(client, target);
				boolean ascend = waypoint.y() > player.getBlockY();
				MinecraftUnderwaterEscapeController.this.movement.moveDirectional(
					client,
					true,
					false,
					false,
					false,
					false,
					ascend,
					tick
				);
			}

			@Override
			public void stop() {
				MinecraftUnderwaterEscapeController.this.movement.stop(activeClient);
			}
		});
	}

	public Snapshot tick(
		MinecraftClient client,
		UnderwaterEscapeSearch.SearchMode requestedMode,
		int remainingAirTicks,
		long tick,
		boolean targetSatisfied
	) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.world == null || player == null) {
			reset(client);
			return snapshot();
		}
		activeClient = client;
		if (searchSession != null
			&& navigator.snapshot().phase() == UnderwaterEscapeNavigator.Phase.REACHED
			&& !targetSatisfied) {
			restartSearch(client);
			activeClient = client;
		}
		if (searchSession == null || mode != requestedMode) {
			begin(client, requestedMode, remainingAirTicks);
		}
		if (searchSession != null && searchStatus == UnderwaterEscapeSearch.SearchStatus.SEARCHING) {
			UnderwaterEscapeSearch.SearchUpdate update = searchSession.advance(
				UnderwaterEscapeSearch.DEFAULT_CELL_INSPECTIONS_PER_TICK
			);
			candidates = update.candidates();
			searchStatus = update.status();
		}
		if (waitingForBaritoneRelease) {
			waitingForBaritoneRelease = !BaritoneReleaseBarrier.releaseAndDrain(baritone);
			if (waitingForBaritoneRelease) {
				movement.stop(client);
				return snapshot();
			}
		}
		UnderwaterEscapeNavigator.Snapshot navigation = navigator.tick(
			candidates,
			new UnderwaterEscapeNavigator.Observation(
				player.getX(),
				player.getY(),
				player.getZ(),
				tick,
				targetSatisfied
			)
		);
		if (navigation.phase() == UnderwaterEscapeNavigator.Phase.RESEARCH_REQUIRED) {
			// Baritone left the player in a connected cell outside every route
			// computed from the old origin. Re-anchor the bounded search here;
			// navigator.restartSearch() deliberately retains attempted/failed targets.
			begin(client, requestedMode, remainingAirTicks);
			movement.stop(client);
			return snapshot();
		}
		return new Snapshot(searchStatus, candidates.size(), navigation, false);
	}

	public void reset(MinecraftClient client) {
		navigator.reset();
		movement.stop(client);
		activeClient = null;
		mode = null;
		searchSession = null;
		candidates = List.of();
		searchStatus = UnderwaterEscapeSearch.SearchStatus.SEARCHING;
		waitingForBaritoneRelease = false;
	}

	public Snapshot snapshot() {
		return new Snapshot(searchStatus, candidates.size(), navigator.snapshot(), waitingForBaritoneRelease);
	}

	private void begin(
		MinecraftClient client,
		UnderwaterEscapeSearch.SearchMode requestedMode,
		int remainingAirTicks
	) {
		navigator.restartSearch();
		waitingForBaritoneRelease = true;
		mode = Objects.requireNonNull(requestedMode, "requestedMode");
		ClientPlayerEntity player = client.player;
		UnderwaterEscapeSearch.Position start = new UnderwaterEscapeSearch.Position(
			player.getBlockX(),
			player.getBlockY(),
			player.getBlockZ()
		);
		UnderwaterEscapeSearch.SearchRequest request = UnderwaterEscapeSearch.SearchRequest.forRemainingAir(
			start,
			requestedMode,
			remainingAirTicks
		);
		searchSession = UnderwaterEscapeSearch.begin(request, new LiveCellView(client, start, request.maxPathSteps()));
		candidates = List.of();
		searchStatus = searchSession.status();
	}

	private static UnderwaterEscapeSearch.Cell observeCell(
		MinecraftClient client,
		UnderwaterEscapeSearch.Position position
	) {
		BlockPos feetPos = new BlockPos(position.x(), position.y(), position.z());
		BlockPos headPos = feetPos.up();
		BlockPos supportPos = feetPos.down();
		if (!client.world.isInBuildLimit(feetPos)
			|| !client.world.isInBuildLimit(headPos)
			|| !client.world.isInBuildLimit(supportPos)
			|| !client.world.isChunkLoaded(feetPos)
			|| !client.world.isChunkLoaded(headPos)
			|| !client.world.isChunkLoaded(supportPos)) {
			return UnderwaterEscapeSearch.Cell.blocked();
		}
		BlockState feet = client.world.getBlockState(feetPos);
		BlockState head = client.world.getBlockState(headPos);
		BlockState support = client.world.getBlockState(supportPos);
		boolean collisionFree = feet.getCollisionShape(client.world, feetPos).isEmpty()
			&& head.getCollisionShape(client.world, headPos).isEmpty();
		if (!collisionFree) {
			return UnderwaterEscapeSearch.Cell.blocked();
		}
		boolean waterAtFeet = client.world.getFluidState(feetPos).isIn(FluidTags.WATER);
		boolean waterAtHead = client.world.getFluidState(headPos).isIn(FluidTags.WATER);
		boolean breathable = client.world.getFluidState(headPos).isEmpty();
		boolean safeStanding = breathable
			&& client.world.getFluidState(feetPos).isEmpty()
			&& support.isSideSolidFullSquare(client.world, supportPos, Direction.UP);
		return new UnderwaterEscapeSearch.Cell(
			true,
			waterAtFeet,
			waterAtHead,
			breathable,
			safeStanding
		);
	}

	/** Predicate shared with idle-drowning resolution; unlike surface memory it rejects water. */
	public static boolean isSafeStandingPosition(MinecraftClient client, BlockPos feetPos) {
		if (client == null || client.world == null || feetPos == null) {
			return false;
		}
		return observeCell(client, new UnderwaterEscapeSearch.Position(
			feetPos.getX(),
			feetPos.getY(),
			feetPos.getZ()
		)).safeStanding();
	}

	private void restartSearch(MinecraftClient client) {
		navigator.restartSearch();
		movement.stop(client);
		mode = null;
		searchSession = null;
		candidates = List.of();
		searchStatus = UnderwaterEscapeSearch.SearchStatus.SEARCHING;
		waitingForBaritoneRelease = true;
	}

	private static final class LiveCellView implements UnderwaterEscapeSearch.CellView {
		private final MinecraftClient client;
		private final UnderwaterEscapeSearch.Position origin;
		private final int maxPathSteps;
		private final Map<UnderwaterEscapeSearch.Position, UnderwaterEscapeSearch.Cell> observed = new HashMap<>();

		private LiveCellView(
			MinecraftClient client,
			UnderwaterEscapeSearch.Position origin,
			int maxPathSteps
		) {
			this.client = client;
			this.origin = origin;
			this.maxPathSteps = maxPathSteps;
		}

		@Override
		public UnderwaterEscapeSearch.Cell cellAt(UnderwaterEscapeSearch.Position position) {
			int distance = Math.abs(position.x() - origin.x())
				+ Math.abs(position.y() - origin.y())
				+ Math.abs(position.z() - origin.z());
			if (distance > maxPathSteps) {
				return UnderwaterEscapeSearch.Cell.blocked();
			}
			return observed.computeIfAbsent(position, candidate -> observeCell(client, candidate));
		}
	}

	public record Snapshot(
		UnderwaterEscapeSearch.SearchStatus searchStatus,
		int candidateCount,
		UnderwaterEscapeNavigator.Snapshot navigation,
		boolean waitingForBaritoneRelease
	) {
		public Snapshot {
			Objects.requireNonNull(searchStatus, "searchStatus");
			Objects.requireNonNull(navigation, "navigation");
		}
	}
}
