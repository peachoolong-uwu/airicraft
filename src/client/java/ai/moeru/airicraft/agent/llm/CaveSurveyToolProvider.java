package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.spatial.VisibleSurfaceSampler;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.Registries;
import net.minecraft.world.RaycastContext;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/** Sparse first-hit rays, not a scan of hidden loaded terrain. */
public final class CaveSurveyToolProvider implements PlannerToolProvider {
	private final Consumer<List<BlockPos>> observed;
	public CaveSurveyToolProvider(Consumer<List<BlockPos>> observed) { this.observed = observed; }
	@Override public String id() { return "cave_survey"; }
	@Override public boolean handles(String name) { return "survey_cave".equals(normalizeName(name)); }
	@Override public List<Map<String, Object>> openAiTools() {
		return List.of(toolForProvider("survey_cave",
			"Survey surrounding visible cave surfaces using sparse first-hit rays. Returns up to eight visible standing candidates, exposed ore and hazards, and current light. Does not find hidden ore or prove routes/absence. No terrain changes.",
			propertiesForProvider(propForProvider("radius", Map.of("type", "integer", "description", "Ray distance, default 12, 4..24 blocks."))), List.of()));
	}
	@Override public void validateArguments(String name, JsonObject args) { radius(args); }
	@Override public String promptInstructions() {
		return "For cave exploration, prepare shield, food, usable pickaxes, torches and free inventory space. Remember the chosen entrance and home before descending. Use survey_cave for compact visible passage/ore observations; select short standing waypoints, navigate and survey again. Configure pathfinding allowBreak=false and allowPlace=false during passage exploration so a blocked route cannot turn into strip mining or bridging. Restore previous settings when leaving this exploration intent. Standing candidates are observations, not guaranteed reachable paths. Remember junctions, tried branches and return waypoints with place memory to avoid repeating dead ends. Maintain lighting; stop at unlit or hazardous drops, replenish supplies and return along remembered waypoints. For ore, use mine_blocks or collect_resource with constraints.visibleOnly=true and a small fixed scope so System 1 approaches visible sources, mines newly exposed faces and collects drops. Keep scope around the chosen vein. Use break_blocks for exact edits already in reach. visibleOnly uses the same sparse first-hit rays as this survey; it does not discover buried sources or change pathfinding terrain permissions. Do not use hidden-block scans or unrestricted mining to select veins. Logbook stock helps plan resupply, but reopen containers to confirm it. A survey can miss small surfaces; no reported ore or waypoint is not proof of absence.";
	}
	@Override public CompletableFuture<String> execute(PlannerToolCall call) {
		CompletableFuture<String> result = new CompletableFuture<>();
		MinecraftClient client = MinecraftClient.getInstance();
		client.execute(() -> {
			try { result.complete(survey(client, radius(call.arguments()))); }
			catch (RuntimeException exception) { result.complete("TOOL_ERROR: survey_cave " + exception.getMessage()); }
		});
		return result;
	}
	private String survey(MinecraftClient client, int radius) {
		if (client.world == null || client.player == null) throw new IllegalStateException("world_not_loaded");
		var player = client.player;
		var world = client.world;
		Vec3d eye = player.getEyePos();
		Map<BlockPos, String> resources = new LinkedHashMap<>();
		Map<BlockPos, String> hazards = new LinkedHashMap<>();
		List<BlockPos> floors = new ArrayList<>();
		for (var hit : VisibleSurfaceSampler.sample(eye, radius, (start, end) ->
			world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.ANY, player)))) {
			if (hit.getType() != HitResult.Type.BLOCK || !world.isChunkLoaded(hit.getBlockPos())) continue;
			BlockPos pos = hit.getBlockPos().toImmutable();
			var state = world.getBlockState(pos);
			String id = Registries.BLOCK.getId(state.getBlock()).toString();
			if (id.endsWith("_ore")) resources.put(pos, id);
			if (!state.getFluidState().isEmpty() || dangerous(id)) hazards.put(pos, id);
			if (hit.getSide() != Direction.UP || dangerous(id) || !state.getFluidState().isEmpty()
				|| !state.isSideSolidFullSquare(world, pos, Direction.UP)) continue;
			BlockPos feet = pos.up();
			if (dangerous(Registries.BLOCK.getId(world.getBlockState(feet).getBlock()).toString())
				|| dangerous(Registries.BLOCK.getId(world.getBlockState(feet.up()).getBlock()).toString())
				|| !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
				|| !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
				|| !world.getFluidState(feet).isEmpty() || !world.getFluidState(feet.up()).isEmpty()) continue;
			if (clearRay(client, eye, Vec3d.ofBottomCenter(feet).add(0, 0.2, 0))
				&& clearRay(client, eye, Vec3d.ofBottomCenter(feet).add(0, 1.62, 0))) floors.add(feet);
		}
		List<BlockPos> waypoints = selectWaypoints(player.getBlockPos(), floors);
		List<Map<String, Object>> ores = records(resources, 16), dangers = records(hazards, 12);
		List<BlockPos> evidence = new ArrayList<>(resources.keySet().stream().limit(16).toList());
		evidence.addAll(hazards.keySet().stream().limit(12).toList());
		evidence.addAll(waypoints);
		observed.accept(List.copyOf(evidence));
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("position", position(player.getBlockPos()));
		output.put("dimension", world.getRegistryKey().getValue().toString());
		output.put("radius", radius);
		output.put("sampledRays", 495);
		output.put("lightAtPlayer", world.getLightLevel(player.getBlockPos()));
		output.put("standingCandidates", waypoints.stream().map(pos -> Map.of("position", position(pos), "light", world.getLightLevel(pos))).toList());
		output.put("exposedOre", ores);
		output.put("hazards", dangers);
		output.put("routeValidated", false);
		return "Tool result for survey_cave: " + new Gson().toJson(output);
	}
	static List<BlockPos> selectWaypoints(BlockPos origin, List<BlockPos> floors) {
		Map<Integer, BlockPos> sectors = new TreeMap<>();
		for (BlockPos pos : floors) {
			int dx = pos.getX() - origin.getX(), dz = pos.getZ() - origin.getZ();
			if (dx * dx + dz * dz < 4) continue;
			int sector = Math.floorMod((int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4)), 8);
			BlockPos previous = sectors.get(sector);
			if (previous == null || pos.getSquaredDistance(origin) > previous.getSquaredDistance(origin)) sectors.put(sector, pos.toImmutable());
		}
		return List.copyOf(sectors.values());
	}
	private static boolean clearRay(MinecraftClient client, Vec3d eye, Vec3d end) {
		return client.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER,
			RaycastContext.FluidHandling.ANY, client.player)).getType() == HitResult.Type.MISS;
	}
	private static boolean dangerous(String id) {
		return Set.of("minecraft:lava", "minecraft:fire", "minecraft:soul_fire", "minecraft:magma_block", "minecraft:campfire",
			"minecraft:soul_campfire", "minecraft:cactus", "minecraft:powder_snow").contains(id);
	}
	private static Map<String, Integer> position(BlockPos pos) { return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()); }
	private static List<Map<String, Object>> records(Map<BlockPos, String> blocks, int limit) {
		return blocks.entrySet().stream().limit(limit).map(entry -> Map.<String, Object>of("position", position(entry.getKey()), "blockId", entry.getValue())).toList();
	}
	private static int radius(JsonObject args) {
		if (!args.has("radius")) return 12;
		try {
			int value = args.get("radius").getAsBigDecimal().intValueExact();
			if (!args.getAsJsonPrimitive("radius").isNumber() || value < 4 || value > 24) throw new ArithmeticException();
			return value;
		} catch (RuntimeException exception) { throw new JsonParseException("radius must be integer 4..24"); }
	}
}
