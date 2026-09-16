package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.os.NativeActionRuntime;
import ai.moeru.airicraft.os.NativeProgressClocks;
import ai.moeru.airicraft.os.NativeProgressRuntime;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.UUID;

/** Client-owned registrations; all counters come from actual server update hooks. */
final class MinecraftProgressAccess {
	private static final Gson JSON = new GsonBuilder().serializeNulls().create();
	private String owner = UUID.randomUUID().toString();
	private String binding;
	private List<NativeProgressClocks.Scope> scopes = List.of();

	JsonObject observe(NativeActionRuntime.World world, JsonArray requested) {
		// Parse before replacing any retained registration, including in an unavailable world.
		var parsed = requested == null ? null : NativeProgressClocks.parse(requested, world.dimension());
		String currentBinding = world.worldId() + "\n" + world.dimension() + "\n" + world.loadId();
		if (!currentBinding.equals(binding)) { binding = currentBinding; owner = UUID.randomUUID().toString(); scopes = List.of(); }
		if (parsed != null) scopes = parsed;
		var client = MinecraftClient.getInstance();
		var result = new JsonObject();
		boolean available = client != null && client.getServer() != null && world.alive();
		result.addProperty("available", available);
		if (!available) { result.add("scopes", new JsonArray()); return result; }
		var snapshot = NativeProgressRuntime.capture(client.getServer(), owner, scopes);
		result.addProperty("source", "native_completed_scope_ticks");
		result.addProperty("clockSession", snapshot.clockSession());
		result.addProperty("throughTick", snapshot.throughTick());
		result.add("scopes", JSON.toJsonTree(snapshot.scopes()));
		return result;
	}
}
