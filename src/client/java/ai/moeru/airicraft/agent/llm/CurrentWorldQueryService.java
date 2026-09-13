package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class CurrentWorldQueryService implements CurrentWorldQueryTool {
	static final int DEFAULT_HORIZONTAL_RADIUS = 8;
	static final int DEFAULT_VERTICAL_RADIUS = 4;
	static final int MAX_HORIZONTAL_RADIUS = 16;
	static final int MAX_VERTICAL_RADIUS = 8;
	static final int DEFAULT_MAX_RESULTS = 32;
	static final int MAX_RESULTS = 64;
	static final int MAX_DISTANCE_FROM_PLAYER = 64;
	static final int SEARCH_BLOCK_CAP = 20000;
	private static final double INTERACTION_RANGE_SQUARED = 20.25D;

	private final Supplier<MinecraftClient> clientSupplier;

	public CurrentWorldQueryService(Supplier<MinecraftClient> clientSupplier) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
	}

	@Override
	public CompletableFuture<String> inspectWorld(JsonObject arguments) {
		return inspectWorldDetailed(arguments).thenApply(WorldQueryResult::text);
	}

	public CompletableFuture<WorldQueryResult> inspectWorldDetailed(JsonObject arguments) {
		MinecraftClient client = clientSupplier.get();
		if (client == null || client.world == null || client.player == null) {
			return CompletableFuture.completedFuture(new WorldQueryResult("WORLD_UNAVAILABLE: world_not_loaded", List.of()));
		}
		try {
			return CompletableFuture.completedFuture(inspectWorld(client, arguments == null ? new JsonObject() : arguments));
		}
		catch (WorldQueryException exception) {
			return CompletableFuture.completedFuture(new WorldQueryResult("TOOL_ERROR: inspect_world " + exception.getMessage(), List.of()));
		}
		catch (RuntimeException exception) {
			return CompletableFuture.completedFuture(new WorldQueryResult("TOOL_ERROR: inspect_world " + safeMessage(exception), List.of()));
		}
	}

	private static WorldQueryResult inspectWorld(MinecraftClient client, JsonObject arguments) {
		String mode = stringArg(arguments, "mode").orElseThrow(() -> new WorldQueryException("mode is required"));
		QueryBounds bounds = QueryBounds.from(client.player.getBlockPos(), arguments);
		ensureWithinDistance(bounds, client.player.getBlockPos());
		ensureWithinBlockCap(bounds);
		return switch (mode) {
			case "inspect_area" -> inspectArea(client.world, client.player, bounds, arguments);
			case "find_blocks" -> findBlocks(client.world, client.player, bounds, arguments);
			case "find_placement_sites" -> findPlacementSites(client.world, client.player, bounds, arguments);
			default -> throw new WorldQueryException("unsupported_mode " + mode);
		};
	}

	private static WorldQueryResult inspectArea(World world, ClientPlayerEntity player, QueryBounds bounds, JsonObject arguments) {
		ArrayList<BlockRecord> records = new ArrayList<>();
		int scanned = 0;
		for (BlockPos pos : bounds.positions()) {
			scanned++;
			if (!world.isChunkLoaded(pos)) {
				records.add(BlockRecord.unloaded(pos, distance(player.getBlockPos(), pos)));
				continue;
			}
			BlockState state = world.getBlockState(pos);
			records.add(BlockRecord.of(pos, state, distance(player.getBlockPos(), pos)));
		}
		int maxResults = boundedInt(arguments, "maxResults", DEFAULT_MAX_RESULTS, 1, MAX_RESULTS);
		return areaResult(bounds, scanned, records, maxResults);
	}

	static WorldQueryResult areaResult(QueryBounds bounds, int scanned, List<BlockRecord> records, int maxResults) {
		List<BlockRecord> safeRecords = records == null ? List.of() : records;
		BlockPos center = bounds.center();
		List<BlockRecord> limited = safeRecords.stream()
			.sorted(Comparator.comparingInt((BlockRecord record) -> distance(center, record.pos()))
				.thenComparing(BlockRecord.ORDERING))
			.limit(maxResults)
			.toList();
		return new WorldQueryResult("Tool result for inspect_world: mode=inspect_area"
			+ " scope=" + bounds.scope()
			+ " bounds=" + bounds.compact()
			+ " scanned=" + scanned
			+ " matched=" + safeRecords.size()
			+ " returned=" + limited.size()
			+ " order=nearest_query_center queryCenter=" + compactPos(center) + " distanceOrigin=player"
			+ " truncated=" + (limited.size() < safeRecords.size())
			+ " blocks=" + formatRecords(limited, maxResults)
			+ (limited.size() < safeRecords.size()
				? "\nResult truncated. Use a smaller box or radius around the blocks you need; omitted positions have not been inspected."
				: ""), limited.stream().map(record -> record.pos().toImmutable()).toList());
	}

	private static WorldQueryResult findBlocks(World world, ClientPlayerEntity player, QueryBounds bounds, JsonObject arguments) {
		List<String> blockIds = stringArrayArg(arguments, "blockIds");
		List<StateFilter> filters = stateFilters(arguments, "stateFilters");
		int maxResults = boundedInt(arguments, "maxResults", DEFAULT_MAX_RESULTS, 1, MAX_RESULTS);
		ArrayList<BlockRecord> matches = new ArrayList<>();
		int scanned = 0;
		for (BlockPos pos : bounds.positions()) {
			scanned++;
			if (scanned > SEARCH_BLOCK_CAP) {
				break;
			}
			if (!world.isChunkLoaded(pos)) {
				continue;
			}
			BlockState state = world.getBlockState(pos);
			if (blockIds.contains(blockId(state)) && matchesFilters(state, filters)) {
				matches.add(BlockRecord.of(pos, state, distance(player.getBlockPos(), pos)));
			}
		}
		matches.sort(BlockRecord.ORDERING);
		List<BlockRecord> limited = matches.stream().limit(maxResults).toList();
		return new WorldQueryResult("Tool result for inspect_world: mode=find_blocks"
			+ " scope=" + bounds.scope()
			+ " bounds=" + bounds.compact()
			+ " scanned=" + Math.min(scanned, SEARCH_BLOCK_CAP)
			+ " matched=" + matches.size()
			+ " returned=" + limited.size()
			+ " blocks=" + formatRecords(limited, maxResults), limited.stream().map(record -> record.pos().toImmutable()).toList());
	}

	private static WorldQueryResult findPlacementSites(World world, ClientPlayerEntity player, QueryBounds bounds, JsonObject arguments) {
		PlacementConstraints constraints = PlacementConstraints.from(arguments);
		int maxResults = boundedInt(arguments, "maxResults", DEFAULT_MAX_RESULTS, 1, MAX_RESULTS);
		ArrayList<PlacementSite> matches = new ArrayList<>();
		int scanned = 0;
		for (BlockPos pos : bounds.positions()) {
			scanned++;
			if (scanned > SEARCH_BLOCK_CAP) {
				break;
			}
			Optional<PlacementSite> site = placementSite(world, player, pos, constraints);
			site.ifPresent(matches::add);
		}
		matches.sort(PlacementSite.ORDERING);
		List<PlacementSite> limited = matches.stream().limit(maxResults).toList();
		return new WorldQueryResult("Tool result for inspect_world: mode=find_placement_sites"
			+ " scope=" + bounds.scope()
			+ " bounds=" + bounds.compact()
			+ " scanned=" + Math.min(scanned, SEARCH_BLOCK_CAP)
			+ " matched=" + matches.size()
			+ " returned=" + limited.size()
			+ " sites=" + formatSites(limited), observedPlacementPositions(limited));
	}

	private static Optional<PlacementSite> placementSite(
		World world,
		ClientPlayerEntity player,
		BlockPos targetPos,
		PlacementConstraints constraints
	) {
		if (!world.isChunkLoaded(targetPos) || !world.isChunkLoaded(targetPos.down())) {
			return Optional.empty();
		}
		BlockState target = world.getBlockState(targetPos);
		if (!constraints.targetMaterial().matches(target)) {
			return Optional.empty();
		}
		BlockPos supportPos = targetPos.down();
		BlockState support = world.getBlockState(supportPos);
		if (!constraints.supportBlockIds().isEmpty() && !constraints.supportBlockIds().contains(blockId(support))) {
			return Optional.empty();
		}
		if (!matchesFilters(support, constraints.supportStateFilters())) {
			return Optional.empty();
		}
		if (constraints.requireSolidTopSupport() && !support.isSideSolidFullSquare(world, supportPos, Direction.UP)) {
			return Optional.empty();
		}
		if (constraints.requireAirAbove()) {
			BlockPos abovePos = targetPos.up();
			if (!world.isChunkLoaded(abovePos)) {
				return Optional.empty();
			}
			BlockState above = world.getBlockState(abovePos);
			if (!above.isAir() && !above.isReplaceable()) {
				return Optional.empty();
			}
		}
		Optional<BlockPos> standableAdjacent = standableAdjacentPosition(world, targetPos);
		if (constraints.requireStandableAdjacent() && standableAdjacent.isEmpty()) {
			return Optional.empty();
		}
		if (constraints.requireWithinInteractionRange() && !withinInteractionRange(player, targetPos)) {
			return Optional.empty();
		}
		BlockPos nearbyRequiredPos = null;
		if (!constraints.nearbyRequiredBlockIds().isEmpty()) {
			Optional<BlockPos> nearbyRequired = nearbyRequiredBlock(world, targetPos, constraints.nearbyRequiredBlockIds(), constraints.nearbyRequiredHorizontalRadius(), constraints.nearbyRequiredVerticalRadius());
			if (nearbyRequired.isEmpty()) {
				return Optional.empty();
			}
			nearbyRequiredPos = nearbyRequired.get();
		}
		return Optional.of(new PlacementSite(
			targetPos,
			blockId(target),
			properties(target),
			supportPos,
			blockId(support),
			properties(support),
			distance(player.getBlockPos(), targetPos),
			standableAdjacent.orElse(null),
			nearbyRequiredPos,
			withinInteractionRange(player, targetPos)
		));
	}

	private static Optional<BlockPos> nearbyRequiredBlock(
		World world,
		BlockPos origin,
		List<String> blockIds,
		int horizontalRadius,
		int verticalRadius
	) {
		for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
			for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
				for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
					BlockPos pos = origin.add(dx, dy, dz);
					if (world.isChunkLoaded(pos) && blockIds.contains(blockId(world.getBlockState(pos)))) {
						return Optional.of(pos.toImmutable());
					}
				}
			}
		}
		return Optional.empty();
	}

	private static List<BlockPos> observedPlacementPositions(List<PlacementSite> sites) {
		ArrayList<BlockPos> positions = new ArrayList<>();
		for (PlacementSite site : sites) {
			positions.add(site.targetPos().toImmutable());
			positions.add(site.supportPos().toImmutable());
			if (site.standableAdjacent() != null) {
				positions.add(site.standableAdjacent().toImmutable());
			}
			if (site.nearbyRequiredPos() != null) {
				positions.add(site.nearbyRequiredPos().toImmutable());
			}
		}
		return List.copyOf(positions);
	}

	private static Optional<BlockPos> standableAdjacentPosition(World world, BlockPos targetPos) {
		for (Direction direction : Direction.Type.HORIZONTAL) {
			BlockPos pos = targetPos.offset(direction);
			if (isStandable(world, pos)) {
				return Optional.of(pos);
			}
		}
		return Optional.empty();
	}

	private static boolean isStandable(World world, BlockPos pos) {
		if (!world.isChunkLoaded(pos) || !world.isChunkLoaded(pos.up()) || !world.isChunkLoaded(pos.down())) {
			return false;
		}
		BlockState feet = world.getBlockState(pos);
		BlockState head = world.getBlockState(pos.up());
		BlockState floor = world.getBlockState(pos.down());
		return (feet.isAir() || feet.isReplaceable())
			&& (head.isAir() || head.isReplaceable())
			&& floor.isSideSolidFullSquare(world, pos.down(), Direction.UP);
	}

	private static boolean withinInteractionRange(ClientPlayerEntity player, BlockPos pos) {
		return player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= INTERACTION_RANGE_SQUARED;
	}

	private static String formatRecords(List<BlockRecord> records, int maxResults) {
		if (records == null || records.isEmpty()) {
			return "none";
		}
		return records.stream()
			.limit(maxResults)
			.map(BlockRecord::compact)
			.collect(Collectors.joining(", ", "[", "]"));
	}

	private static String formatSites(List<PlacementSite> sites) {
		if (sites == null || sites.isEmpty()) {
			return "none";
		}
		return sites.stream()
			.map(PlacementSite::compact)
			.collect(Collectors.joining(", ", "[", "]"));
	}

	private static List<StateFilter> stateFilters(JsonObject arguments, String key) {
		if (arguments == null || !arguments.has(key) || arguments.get(key).isJsonNull()) {
			return List.of();
		}
		ArrayList<StateFilter> filters = new ArrayList<>();
		JsonArray array = arguments.getAsJsonArray(key);
		for (JsonElement element : array) {
			String raw = element.getAsString();
			int separator = raw.indexOf('=');
			filters.add(new StateFilter(raw.substring(0, separator), raw.substring(separator + 1)));
		}
		return List.copyOf(filters);
	}

	private static boolean matchesFilters(BlockState state, List<StateFilter> filters) {
		for (StateFilter filter : filters) {
			if (!filter.value().equals(propertyValue(state, filter.name()))) {
				return false;
			}
		}
		return true;
	}

	private static String blockId(BlockState state) {
		return Registries.BLOCK.getId(state.getBlock()).toString();
	}

	private static Map<String, String> properties(BlockState state) {
		LinkedHashMap<String, String> properties = new LinkedHashMap<>();
		for (Property<?> property : state.getProperties()) {
			properties.put(property.getName(), propertyValue(state, property));
		}
		return java.util.Collections.unmodifiableMap(properties);
	}

	private static String propertyValue(BlockState state, String name) {
		for (Property<?> property : state.getProperties()) {
			if (property.getName().equals(name)) {
				return propertyValue(state, property);
			}
		}
		return null;
	}

	private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
		return property.name(state.get(property));
	}

	private static int distance(BlockPos origin, BlockPos pos) {
		return Math.max(
			Math.max(Math.abs(pos.getX() - origin.getX()), Math.abs(pos.getY() - origin.getY())),
			Math.abs(pos.getZ() - origin.getZ())
		);
	}

	private static int boundedInt(JsonObject arguments, String key, int defaultValue, int min, int max) {
		if (arguments == null || !arguments.has(key) || arguments.get(key).isJsonNull()) {
			return defaultValue;
		}
		int value = arguments.get(key).getAsInt();
		return Math.max(min, Math.min(value, max));
	}

	private static List<String> stringArrayArg(JsonObject arguments, String key) {
		if (arguments == null || !arguments.has(key) || arguments.get(key).isJsonNull()) {
			return List.of();
		}
		ArrayList<String> values = new ArrayList<>();
		for (JsonElement element : arguments.getAsJsonArray(key)) {
			values.add(element.getAsString());
		}
		return List.copyOf(values);
	}

	private static Optional<String> stringArg(JsonObject arguments, String key) {
		if (arguments == null || !arguments.has(key) || arguments.get(key).isJsonNull()) {
			return Optional.empty();
		}
		String value = arguments.get(key).getAsString();
		return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
	}

	private static boolean booleanArg(JsonObject arguments, String key, boolean defaultValue) {
		if (arguments == null || !arguments.has(key) || arguments.get(key).isJsonNull()) {
			return defaultValue;
		}
		return arguments.get(key).getAsBoolean();
	}

	private static void ensureWithinDistance(QueryBounds bounds, BlockPos playerPos) {
		for (BlockPos corner : bounds.corners()) {
			if (distance(playerPos, corner) > MAX_DISTANCE_FROM_PLAYER) {
				throw new WorldQueryException("target_too_far maxDistance=" + MAX_DISTANCE_FROM_PLAYER);
			}
		}
	}

	private static void ensureWithinBlockCap(QueryBounds bounds) {
		long blockCount = bounds.blockCount();
		if (blockCount > SEARCH_BLOCK_CAP) {
			throw new WorldQueryException("query_too_large maxBlocks=" + SEARCH_BLOCK_CAP + " requestedBlocks=" + blockCount);
		}
	}

	private static String safeMessage(RuntimeException exception) {
		String message = exception.getMessage();
		return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
	}

	record BlockRecord(BlockPos pos, String blockId, Map<String, String> properties, boolean loaded, boolean replaceable, boolean air, boolean fluid, int distance) {
		static final Comparator<BlockRecord> ORDERING = Comparator
			.comparingInt(BlockRecord::distance)
			.thenComparingInt(record -> record.pos().getX())
			.thenComparingInt(record -> record.pos().getY())
			.thenComparingInt(record -> record.pos().getZ());

		static BlockRecord unloaded(BlockPos pos, int distance) {
			return new BlockRecord(pos, "unloaded", Map.of(), false, false, false, false, distance);
		}

		static BlockRecord of(BlockPos pos, BlockState state, int distance) {
			return new BlockRecord(
				pos,
				CurrentWorldQueryService.blockId(state),
				CurrentWorldQueryService.properties(state),
				true,
				state.isReplaceable(),
				state.isAir(),
				!state.getFluidState().isEmpty(),
				distance
			);
		}

		String compact() {
			return "{pos=" + compactPos(pos)
				+ ", id=" + blockId
				+ (properties.isEmpty() ? "" : ", state=" + properties)
				+ ", loaded=" + loaded
				+ ", replaceable=" + replaceable
				+ ", air=" + air
				+ ", fluid=" + fluid
				+ ", distance=" + distance
				+ "}";
		}
	}

	record PlacementSite(
		BlockPos targetPos,
		String targetBlockId,
		Map<String, String> targetProperties,
		BlockPos supportPos,
		String supportBlockId,
		Map<String, String> supportProperties,
		int distance,
		BlockPos standableAdjacent,
		BlockPos nearbyRequiredPos,
		boolean withinInteractionRange
	) {
		static final Comparator<PlacementSite> ORDERING = Comparator
			.comparingInt(PlacementSite::distance)
			.thenComparingInt(site -> site.targetPos().getX())
			.thenComparingInt(site -> site.targetPos().getY())
			.thenComparingInt(site -> site.targetPos().getZ());

		String compact() {
			return "{targetPos=" + compactPos(targetPos)
				+ ", targetBlockId=" + targetBlockId
				+ (targetProperties.isEmpty() ? "" : ", targetState=" + targetProperties)
				+ ", supportPos=" + compactPos(supportPos)
				+ ", supportBlockId=" + supportBlockId
				+ (supportProperties.isEmpty() ? "" : ", supportState=" + supportProperties)
				+ ", distance=" + distance
				+ ", standableAdjacent=" + (standableAdjacent == null ? "none" : compactPos(standableAdjacent))
				+ ", nearbyRequiredPos=" + (nearbyRequiredPos == null ? "none" : compactPos(nearbyRequiredPos))
				+ ", withinInteractionRange=" + withinInteractionRange
				+ "}";
		}
	}

	public record WorldQueryResult(String text, List<BlockPos> observedPositions) {
		public WorldQueryResult {
			text = text == null ? "" : text;
			observedPositions = observedPositions == null
				? List.of()
				: observedPositions.stream()
					.filter(Objects::nonNull)
					.map(BlockPos::toImmutable)
					.toList();
		}
	}

	private record StateFilter(String name, String value) {
	}

	private record PlacementConstraints(
		TargetMaterial targetMaterial,
		List<String> supportBlockIds,
		List<StateFilter> supportStateFilters,
		boolean requireSolidTopSupport,
		boolean requireAirAbove,
		boolean requireStandableAdjacent,
		boolean requireWithinInteractionRange,
		List<String> nearbyRequiredBlockIds,
		int nearbyRequiredHorizontalRadius,
		int nearbyRequiredVerticalRadius
	) {
		static PlacementConstraints from(JsonObject arguments) {
			return new PlacementConstraints(
				TargetMaterial.from(stringArg(arguments, "targetMaterial").orElse("air_or_replaceable")),
				stringArrayArg(arguments, "supportBlockIds"),
				stateFilters(arguments, "supportStateFilters"),
				booleanArg(arguments, "requireSolidTopSupport", false),
				booleanArg(arguments, "requireAirAbove", false),
				booleanArg(arguments, "requireStandableAdjacent", true),
				booleanArg(arguments, "requireWithinInteractionRange", false),
				stringArrayArg(arguments, "nearbyRequiredBlockIds"),
				boundedInt(arguments, "nearbyRequiredHorizontalRadius", 4, 0, MAX_HORIZONTAL_RADIUS),
				boundedInt(arguments, "nearbyRequiredVerticalRadius", 1, 0, MAX_VERTICAL_RADIUS)
			);
		}
	}

	private enum TargetMaterial {
		AIR,
		REPLACEABLE,
		AIR_OR_REPLACEABLE;

		static TargetMaterial from(String value) {
			return switch (value) {
				case "air" -> AIR;
				case "replaceable" -> REPLACEABLE;
				case "air_or_replaceable" -> AIR_OR_REPLACEABLE;
				default -> throw new WorldQueryException("unsupported_targetMaterial " + value);
			};
		}

		boolean matches(BlockState state) {
			return switch (this) {
				case AIR -> state.isAir();
				case REPLACEABLE -> !state.isAir() && state.isReplaceable();
				case AIR_OR_REPLACEABLE -> state.isAir() || state.isReplaceable();
			};
		}
	}

	record QueryBounds(String scope, BlockPos min, BlockPos max) {
		BlockPos center() {
			return new BlockPos(min.getX() + (max.getX() - min.getX()) / 2,
				min.getY() + (max.getY() - min.getY()) / 2,
				min.getZ() + (max.getZ() - min.getZ()) / 2);
		}

		static QueryBounds from(BlockPos playerPos, JsonObject arguments) {
			String scope = stringArg(arguments, "scope").orElseThrow(() -> new WorldQueryException("scope is required"));
			return switch (scope) {
				case "self" -> {
					int horizontalRadius = boundedInt(arguments, "horizontalRadius", DEFAULT_HORIZONTAL_RADIUS, 0, MAX_HORIZONTAL_RADIUS);
					int verticalRadius = boundedInt(arguments, "verticalRadius", DEFAULT_VERTICAL_RADIUS, 0, MAX_VERTICAL_RADIUS);
					yield centered(scope, playerPos, horizontalRadius, verticalRadius);
				}
				case "center" -> {
					BlockPos center = new BlockPos(intArg(arguments, "x"), intArg(arguments, "y"), intArg(arguments, "z"));
					int horizontalRadius = boundedInt(arguments, "horizontalRadius", DEFAULT_HORIZONTAL_RADIUS, 0, MAX_HORIZONTAL_RADIUS);
					int verticalRadius = boundedInt(arguments, "verticalRadius", DEFAULT_VERTICAL_RADIUS, 0, MAX_VERTICAL_RADIUS);
					yield centered(scope, center, horizontalRadius, verticalRadius);
				}
				case "box" -> box(
					new BlockPos(intArg(arguments, "x1"), intArg(arguments, "y1"), intArg(arguments, "z1")),
					new BlockPos(intArg(arguments, "x2"), intArg(arguments, "y2"), intArg(arguments, "z2"))
				);
				default -> throw new WorldQueryException("unsupported_scope " + scope);
			};
		}

		private static QueryBounds centered(String scope, BlockPos center, int horizontalRadius, int verticalRadius) {
			return new QueryBounds(
				scope,
				center.add(-horizontalRadius, -verticalRadius, -horizontalRadius),
				center.add(horizontalRadius, verticalRadius, horizontalRadius)
			);
		}

		private static QueryBounds box(BlockPos left, BlockPos right) {
			return new QueryBounds(
				"box",
				new BlockPos(Math.min(left.getX(), right.getX()), Math.min(left.getY(), right.getY()), Math.min(left.getZ(), right.getZ())),
				new BlockPos(Math.max(left.getX(), right.getX()), Math.max(left.getY(), right.getY()), Math.max(left.getZ(), right.getZ()))
			);
		}

		List<BlockPos> corners() {
			return List.of(
				new BlockPos(min.getX(), min.getY(), min.getZ()),
				new BlockPos(min.getX(), min.getY(), max.getZ()),
				new BlockPos(min.getX(), max.getY(), min.getZ()),
				new BlockPos(min.getX(), max.getY(), max.getZ()),
				new BlockPos(max.getX(), min.getY(), min.getZ()),
				new BlockPos(max.getX(), min.getY(), max.getZ()),
				new BlockPos(max.getX(), max.getY(), min.getZ()),
				new BlockPos(max.getX(), max.getY(), max.getZ())
			);
		}

		List<BlockPos> positions() {
			ArrayList<BlockPos> positions = new ArrayList<>();
			for (int x = min.getX(); x <= max.getX(); x++) {
				for (int y = min.getY(); y <= max.getY(); y++) {
					for (int z = min.getZ(); z <= max.getZ(); z++) {
						positions.add(new BlockPos(x, y, z));
					}
				}
			}
			return List.copyOf(positions);
		}

		long blockCount() {
			return (long) (max.getX() - min.getX() + 1)
				* (long) (max.getY() - min.getY() + 1)
				* (long) (max.getZ() - min.getZ() + 1);
		}

		String compact() {
			return compactPos(min) + ".." + compactPos(max);
		}
	}

	private static int intArg(JsonObject arguments, String key) {
		if (arguments == null || !arguments.has(key) || !arguments.get(key).isJsonPrimitive()) {
			throw new WorldQueryException(key + " is required");
		}
		return arguments.get(key).getAsInt();
	}

	private static String compactPos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static final class WorldQueryException extends RuntimeException {
		private WorldQueryException(String message) {
			super(message);
		}
	}
}
