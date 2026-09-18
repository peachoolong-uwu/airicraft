package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.policy.PolicyRuntime;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/** Finite singleplayer adapter. Only the player's already-open container is observable. */
final class ContainerPolicyHost implements PolicyRuntime.Host {
	private enum Phase { IDLE, OBSERVE, BEFORE_WITHDRAW, VERIFY_WITHDRAW, VERIFY_CLOSE, CLOSED }
	record View(int syncId, Map<String, Integer> container, Map<String, Integer> inventory) { }
	private static final Gson JSON = new Gson();
	private final MinecraftClient client;
	private final MinecraftServer server;
	private final GenericContainerScreenHandler screen;
	private final java.util.UUID playerId;
	private GenericContainerScreenHandler serverScreen; // Read and assigned only on the server thread.
	private Phase phase = Phase.IDLE;
	private CompletableFuture<View> reading;
	private CompletableFuture<JsonElement> result;
	private List<ContainerInventoryController.TransferItem> items = List.of();
	private View expected;
	private int elapsed;

	ContainerPolicyHost(MinecraftClient client) {
		if (client == null || client.world == null || client.player == null || client.getServer() == null)
			throw new IllegalStateException("policy_requires_singleplayer");
		if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler))
			throw new IllegalStateException("policy_requires_open_container");
		if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("cursor_not_empty");
		this.client = client;
		server = client.getServer();
		screen = handler;
		playerId = client.player.getUuid();
	}

	@Override public CompletableFuture<JsonElement> execute(JsonObject effect) {
		if (phase != Phase.IDLE) throw new IllegalStateException("policy_host_busy");
		if (!effect.keySet().equals(java.util.Set.of("operation", "arguments"))) throw new IllegalArgumentException("invalid_policy_effect");
		String operation = effect.get("operation").getAsString();
		JsonObject args = effect.getAsJsonObject("arguments");
		requireBoundScreen();
		switch (operation) {
			case "observe_container" -> {
				if (!args.isEmpty()) throw new IllegalArgumentException("observe_container_takes_no_arguments");
				phase = Phase.OBSERVE;
			}
			case "withdraw" -> {
				if (!args.keySet().equals(java.util.Set.of("syncId", "items"))) throw new IllegalArgumentException("invalid_withdraw_arguments");
				requireSyncId(args);
				var parsed = new java.util.ArrayList<ContainerInventoryController.TransferItem>();
				for (JsonElement entry : args.getAsJsonArray("items")) {
					JsonObject item = entry.getAsJsonObject();
					if (!item.keySet().equals(java.util.Set.of("itemId", "quantity"))) throw new IllegalArgumentException("invalid_withdraw_item");
					int quantity = item.get("quantity").getAsBigDecimal().intValueExact();
					if (quantity < 1 || quantity > 2304) throw new IllegalArgumentException("invalid_withdraw_quantity");
					parsed.add(new ContainerInventoryController.TransferItem(item.get("itemId").getAsString(), quantity));
				}
				if (parsed.isEmpty() || parsed.size() > 36) throw new IllegalArgumentException("invalid_withdraw_items");
				items = List.copyOf(parsed);
				phase = Phase.BEFORE_WITHDRAW;
			}
			case "close_container" -> {
				if (!args.keySet().equals(java.util.Set.of("syncId"))) throw new IllegalArgumentException("invalid_close_arguments");
				requireSyncId(args);
				ContainerInventoryController.close(client);
				phase = Phase.VERIFY_CLOSE;
			}
			default -> throw new IllegalArgumentException("unsupported_policy_operation: " + operation);
		}
		elapsed = 0;
		result = new CompletableFuture<>();
		reading = readServer();
		return result;
	}

	@Override public void tick() {
		if (phase == Phase.IDLE || phase == Phase.CLOSED) return;
		if (++elapsed > 100) throw new IllegalStateException("container_confirmation_timeout; inspect current counts before retrying");
		if (client.getServer() != server || client.player == null) throw new IllegalStateException("world_changed");
		if (phase != Phase.VERIFY_CLOSE) requireBoundScreen();
		if (!reading.isDone()) return;
		View observed = reading.join();
		if (phase == Phase.VERIFY_CLOSE) {
			if (observed == null) { complete(JSON.toJsonTree(Map.of("closed", true, "syncId", screen.syncId))); return; }
		} else {
			if (observed == null) throw new IllegalStateException("server_container_changed");
			if (phase == Phase.OBSERVE) { complete(JSON.toJsonTree(observed)); return; }
			if (phase == Phase.BEFORE_WITHDRAW) {
				// Do not plan clicks against speculative client contents.
				View local = view(screen, client.player.getInventory());
				if (!local.equals(observed)) { reading = readServer(); return; }
				expected = afterWithdrawal(observed, items);
				ContainerInventoryController.transfer(client, screen.syncId, "withdraw", items);
				phase = Phase.VERIFY_WITHDRAW;
			} else if (expected.equals(observed)) {
				complete(JSON.toJsonTree(observed));
				return;
			}
		}
		reading = readServer();
	}

	static View afterWithdrawal(View before, List<ContainerInventoryController.TransferItem> items) {
		var container = new TreeMap<>(before.container());
		var inventory = new TreeMap<>(before.inventory());
		for (var item : items) {
			int remaining = container.getOrDefault(item.itemId(), 0) - item.quantity();
			if (remaining < 0) throw new IllegalStateException("insufficient_source_items");
			if (remaining == 0) container.remove(item.itemId()); else container.put(item.itemId(), remaining);
			inventory.merge(item.itemId(), item.quantity(), Integer::sum);
		}
		return new View(before.syncId(), Map.copyOf(container), Map.copyOf(inventory));
	}

	private CompletableFuture<View> readServer() {
		return CompletableFuture.supplyAsync(() -> {
			var player = server.getPlayerManager().getPlayer(playerId);
			if (player == null || !(player.currentScreenHandler instanceof GenericContainerScreenHandler handler) || handler.syncId != screen.syncId)
				return null;
			if (serverScreen == null) serverScreen = handler;
			if (handler != serverScreen) throw new IllegalStateException("server_container_replaced");
			return view(handler, player.getInventory());
		}, server::execute);
	}

	private static View view(GenericContainerScreenHandler handler, net.minecraft.entity.player.PlayerInventory playerInventory) {
		var container = new TreeMap<String, Integer>();
		var inventory = new TreeMap<String, Integer>();
		for (var slot : handler.slots) {
			var stack = slot.getStack();
			if (stack.isEmpty()) continue;
			String id = Registries.ITEM.getId(stack.getItem()).toString();
			if (slot.id < handler.getRows() * 9) container.merge(id, stack.getCount(), Integer::sum);
			else if (slot.inventory == playerInventory && slot.getIndex() < 36) inventory.merge(id, stack.getCount(), Integer::sum);
		}
		return new View(handler.syncId, Map.copyOf(container), Map.copyOf(inventory));
	}

	private void requireSyncId(JsonObject args) {
		if (args.get("syncId").getAsBigDecimal().intValueExact() != screen.syncId) throw new IllegalStateException("container_identity_mismatch");
	}

	private void requireBoundScreen() {
		if (client.player == null || client.player.currentScreenHandler != screen) throw new IllegalStateException("container_changed");
		if (!screen.getCursorStack().isEmpty()) throw new IllegalStateException("cursor_not_empty");
	}

	private void complete(JsonElement value) {
		phase = Phase.IDLE;
		result.complete(value);
	}

	@Override public void close() {
		phase = Phase.CLOSED;
		if (result != null && !result.isDone()) result.completeExceptionally(new IllegalStateException("policy_cancelled"));
	}
}
