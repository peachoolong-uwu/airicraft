package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;

/** Bounded, read-only readiness for externally scheduled crop passes. */
public final class CropPlotObservation {
	private CropPlotObservation() {}

	public static JsonObject inspect(CropTendingStepArgs args) {
		var environment = new MinecraftCropTendingEnvironment(new CameraController());
		String error = environment.validate(args);
		if (error != null) throw new IllegalArgumentException(error);
		int mature = 0, empty = 0, growing = 0, unknown = 0;
		for (long x = args.x1(); x <= args.x2(); x++) {
			for (long z = args.z1(); z <= args.z2(); z++) {
				switch (environment.state(new GoalPosition((int) x, args.y(), (int) z, true), args)) {
					case MATURE -> mature++;
					case EMPTY_FARMLAND -> empty++;
					case GROWING -> growing++;
					case UNLOADED -> unknown++;
					case OTHER -> { }
				}
			}
		}
		var client = MinecraftClient.getInstance();
		JsonObject result = new JsonObject();
		result.addProperty("worldIdentity", Integer.toHexString(System.identityHashCode(client.world)));
		result.addProperty("dimension", client.world.getRegistryKey().getValue().toString());
		result.addProperty("worldTick", client.world.getTime());
		result.addProperty("paused", client.isPaused());
		result.addProperty("playerAlive", client.player.isAlive());
		result.addProperty("hookActive", client.player.fishHook != null);
		result.addProperty("known", unknown == 0);
		result.addProperty("mature", mature);
		result.addProperty("emptyFarmland", empty);
		result.addProperty("growing", growing);
		result.addProperty("unknown", unknown);
		int seeds = environment.seedCount(args.seedItemId());
		result.addProperty("seeds", seeds);
		result.addProperty("ready", unknown == 0 && (mature > 0 || (empty > 0 && seeds > 0)));
		return result;
	}
}
