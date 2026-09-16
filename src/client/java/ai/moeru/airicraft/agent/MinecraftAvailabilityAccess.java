package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.os.NativeActionRuntime;
import ai.moeru.airicraft.os.NativeAvailabilityRuntime;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static ai.moeru.airicraft.agent.NativeContainerActions.*;
import static ai.moeru.airicraft.os.NativeActionRuntime.*;

/** Server-owned material reads paired with immutable client authority notifications. */
final class MinecraftAvailabilityAccess {
	private static final Gson JSON = new GsonBuilder().serializeNulls().create();
	private Source source;
	record Binding(MinecraftServer server, UUID playerId, String worldId, String dimension, String loadId) {}
	record OpenWindow(String id, int syncId) {}
	private record State(AvailabilityGate gate, OpenWindow window, long revision, long pulse) {}

	void update(Binding binding, AvailabilityGate gate, OpenWindow window) {
		if (binding == null) {
			if (source != null) NativeAvailabilityRuntime.unregister(source.binding.server, source.owner);
			source = null;
			return;
		}
		if (source == null || !source.binding.equals(binding)) source = new Source(binding);
		State previous = source.state;
		boolean changed = previous == null || !previous.gate.world().equals(gate.world()) || !Objects.equals(previous.gate.lease(), gate.lease())
			|| previous.gate.idle() != gate.idle() || !Objects.equals(previous.window, window);
		long revision = previous == null ? 1 : previous.revision + (changed ? 1 : 0);
		// Publish first: a server callback can never resume an older gate after its invalidation.
		source.state = new State(gate, window, revision, System.nanoTime());
		NativeAvailabilityRuntime.register(binding.server, source.owner, source);
		if (changed) NativeAvailabilityRuntime.invalidate(binding.server, source.owner);
	}

	JsonObject proof(Window window, List<Slot> inventory) {
		var result = new JsonObject(); result.addProperty("available", false);
		Source current = source;
		if (current == null) return result;
		var snapshot = NativeAvailabilityRuntime.snapshot(current.binding.server, current.owner);
		if (snapshot == null) return result;
		State state = current.state;
		result = JSON.toJsonTree(snapshot).getAsJsonObject();
		result.addProperty("source", "native_stable_material_ticks");
		result.addProperty("gateRevision", state.revision);
		if (inventory == null || state.gate.lease() == null || !snapshot.available() || !Objects.equals(snapshot.stamp(), stamp(state, window, inventory))) {
			result.addProperty("available", false);
			result.add("fromTick", com.google.gson.JsonNull.INSTANCE);
			result.add("stamp", com.google.gson.JsonNull.INSTANCE);
		}
		return result;
	}

	private static String stamp(State state, Window window, List<Slot> inventory) {
		var material = JSON.toJsonTree(Map.of("world", state.gate.world(), "lease", state.gate.lease(), "gateRevision", state.revision,
			"window", window, "inventory", Map.of("available", true, "slots", inventory))).getAsJsonObject();
		return NativeActionRuntime.fingerprint("eligibility-v1", "material", material);
	}

	private static final class Source implements NativeAvailabilityRuntime.Sampler {
		private final Binding binding;
		private final String owner = UUID.randomUUID().toString();
		private volatile State state;
		// Only the owning server thread touches the material cache.
		private final Map<Integer, CachedStack> stacks = new HashMap<>();
		private ScreenHandler handler;
		private OpenWindow windowBinding;
		private Source(Binding binding) { this.binding = binding; }
		@Override public String capture(MinecraftServer server) {
			State current = state;
			long now = System.nanoTime();
			if (server != binding.server || current == null || current.gate.lease() == null || !current.gate.idle() || !current.gate.world().alive()
				|| current.gate.world().controllerBusy() || current.gate.world().reflexActive() || now < current.pulse || now - current.pulse >= 2_000_000_000L
				|| now < current.gate.heartbeatAtNanos() || now - current.gate.heartbeatAtNanos() >= 5_000_000_000L) return null;
			var player = server.getPlayerManager().getPlayer(binding.playerId);
			if (player == null || !player.isAlive() || player.isSpectator() || player.isUsingItem() || player.fishHook != null
				|| !player.getWorld().getRegistryKey().getValue().toString().equals(binding.dimension)) return null;
			var activeHandler = player.currentScreenHandler;
			if (activeHandler != player.playerScreenHandler && (!(activeHandler instanceof GenericContainerScreenHandler) || activeHandler.slots.size() > 90
				|| current.window == null || current.window.syncId != activeHandler.syncId || !activeHandler.canUse(player))) return null;
			// A client window token binds to exactly one server handler, including recycled sync IDs.
			if (!Objects.equals(windowBinding, current.window)) {
				windowBinding = current.window; handler = activeHandler; stacks.clear();
			} else if (handler != null && handler != activeHandler && activeHandler != player.playerScreenHandler) return null;
			else if (handler == null) handler = activeHandler;
			var inventory = new ArrayList<Slot>(36);
			for (int index = 0; index < 36; index++) inventory.add(stack(player, index, index, false, player.getInventory().getStack(index)));
			Slot cursor = stack(player, 200, -1, false, activeHandler.getCursorStack());
			Window window;
			if (activeHandler == player.playerScreenHandler) window = new Window(false, "", 0, List.of(), cursor);
			else window = MinecraftContainerAccess.window(player, activeHandler, current.window.id,
				(slot, container, value) -> stack(player, slot < 0 ? 200 : 100 + slot, slot, container, value));
			return stamp(current, window, inventory);
		}
		private Slot stack(PlayerEntity player, int key, int slot, boolean container, ItemStack value) {
			CachedStack cached = stacks.get(key);
			if (cached == null || !ItemStack.areEqual(cached.item, value)) {
				cached = new CachedStack(value.copy(), MinecraftContainerAccess.stack(player, slot, container, value));
				stacks.put(key, cached);
			}
			return cached.slot;
		}
	}
	private record CachedStack(ItemStack item, Slot slot) {}
}
