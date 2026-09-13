package ai.moeru.airicraft.agent.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.*;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import java.util.*;

final class LocalSpatialQuery {
	private LocalSpatialQuery() { }
	static CurrentWorldQueryService.WorldQueryResult inspect(MinecraftClient client, String mode, BlockPos center, JsonObject args) {
		var observed = new LinkedHashSet<BlockPos>();
		var report = new LinkedHashMap<String,Object>();
		report.put("mode",mode); report.put("routeGuarantee",false);
		if (mode.equals("check_position")) {
			double feetY = args.has("feetY") ? args.get("feetY").getAsDouble() : center.getY();
			if (!Double.isFinite(feetY) || Math.abs(feetY-center.getY())>1) throw new IllegalArgumentException("feetY must be within one block of query y");
			var feet = new Vec3d(center.getX()+.5, feetY, center.getZ()+.5);
			report.put("position", coordinates(feet)); report.put("standing", standing(client,feet,observed));
		} else {
			report.put("targetBlock",coordinates(Vec3d.of(center)));
			report.put("fromCurrentEyes", interaction(client,client.player.getEyePos(),center,observed));
			var approaches = new ArrayList<Object>();
			for (BlockPos pos : BlockPos.iterate(center.add(-3,-2,-3),center.add(3,2,3))) {
				if (approaches.size() >= 8) break;
				if (!client.world.isChunkLoaded(pos.down())) continue;
				// Include partial support surfaces, not only integer feet heights.
				var floor = pos.down();
				for (var shape : client.world.getBlockState(floor).getCollisionShape(client.world,floor).getBoundingBoxes()) {
					Vec3d feet = new Vec3d(pos.getX()+.5,floor.getY()+shape.maxY,pos.getZ()+.5);
					var standing = standing(client,feet,observed);
					if (!standing.standable()) continue;
					var interaction = interaction(client,feet.add(0,1.62,0),center,observed);
					if (Boolean.TRUE.equals(interaction.get("reachable")) && "clear".equals(interaction.get("lineOfSight"))) {
						approaches.add(Map.of("feet",coordinates(feet),"standing",standing,"interaction",interaction)); break;
					}
				}
			}
			report.put("suitableApproaches",approaches);
			report.put("approachSearch", "bounded local candidates; absence is not proof that no approach or route exists");
			if (client.world.isChunkLoaded(center)) DoorPassageGeometry.describe(client.world,center).ifPresent(value -> report.put("doorPassage",value));
		}
		return new CurrentWorldQueryService.WorldQueryResult("Tool result for inspect_world: " + new Gson().toJson(report),List.copyOf(observed));
	}
	private static StandingGeometry.Assessment standing(MinecraftClient client, Vec3d feet, Set<BlockPos> observed) {
		BlockPos p = BlockPos.ofFloored(feet); var shapes = new ArrayList<Box>(); boolean loaded = true;
		for (BlockPos cell : BlockPos.iterate(p.add(-1,-1,-1),p.add(1,2,1))) {
			if (!client.world.isChunkLoaded(cell)) { loaded=false; continue; }
			observed.add(cell.toImmutable());
			for (Box b : client.world.getBlockState(cell).getCollisionShape(client.world,cell).getBoundingBoxes()) shapes.add(b.offset(cell));
		}
		return StandingGeometry.assess(feet.x,feet.y,feet.z,shapes,loaded);
	}
	private static Map<String,Object> interaction(MinecraftClient client, Vec3d eyes, BlockPos target, Set<BlockPos> observed) {
		var result = new LinkedHashMap<String,Object>();
		Vec3d end = Vec3d.ofCenter(target);
		boolean loaded = true;
		for (int i=0;i<=32;i++) {
			BlockPos cell = BlockPos.ofFloored(eyes.lerp(end,i/32.0));
			if (!client.world.isChunkLoaded(cell)) loaded=false; else observed.add(cell);
		}
		result.put("eyes",coordinates(eyes)); result.put("distanceToCenter",eyes.distanceTo(end));
		double reach = client.player.getBlockInteractionRange();
		result.put("reach",reach);
		if (!loaded) { result.put("lineOfSight","unknown"); result.put("reachable","unknown"); return result; }
		var hit = client.world.raycast(new RaycastContext(eyes,end,RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.NONE,client.player));
		boolean clear = hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
		result.put("lineOfSight", clear ? "clear" : hit.getType()==HitResult.Type.MISS ? "no_target_collision" : "obstructed");
		result.put("reachable", eyes.squaredDistanceTo(hit.getPos())<=reach*reach);
		if (!clear && hit.getType()==HitResult.Type.BLOCK) result.put("obstruction",coordinates(Vec3d.of(hit.getBlockPos())));
		return result;
	}
	private static Map<String,Double> coordinates(Vec3d v) { return Map.of("x",v.x,"y",v.y,"z",v.z); }
}
