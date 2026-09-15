package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.os.NativeActionRuntime;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static ai.moeru.airicraft.agent.NativeContainerActions.*;
import static ai.moeru.airicraft.os.NativeActionRuntime.Rejected;

/** Minecraft thread adapter. Server reads are restricted to the player's already-open handler. */
final class MinecraftContainerAccess implements Access {
	private final BooleanSupplier ordinaryBusy;
	private final BooleanSupplier reflexOwns;
	private final CameraController camera;
	private final BaritoneFacade baritone;
	private final LinkedHashMap<String, Context> contexts = new LinkedHashMap<>();
	private Object lastWorld;
	private Object lastConnection;
	private Object lastPlayer;
	private boolean lastAlive;
	private long loadGeneration;
	private Context current;
	private Context retained;

	MinecraftContainerAccess(BooleanSupplier ordinaryBusy, BooleanSupplier reflexOwns, CameraController camera, BaritoneFacade baritone) {
		this.ordinaryBusy = ordinaryBusy;
		this.reflexOwns = reflexOwns;
		this.camera = camera;
		this.baritone = baritone;
	}

	@Override public boolean available() {
		var client = MinecraftClient.getInstance();
		return client != null && client.getServer() != null;
	}

	@Override public NativeActionRuntime.World world() {
		var client = MinecraftClient.getInstance();
		Object world = client == null ? null : client.world;
		Object connection = client == null ? null : client.getNetworkHandler();
		var player = client == null ? null : client.player;
		boolean alive = world != null && player != null && player.isAlive();
		if (world != lastWorld || connection != lastConnection || player != lastPlayer || (lastAlive && !alive)) {
			loadGeneration++;
			contexts.values().removeIf(context -> context != retained);
			current = null;
		}
		lastWorld = world;
		lastConnection = connection;
		lastPlayer = player;
		lastAlive = alive;
		String identity = client != null && client.getServer() != null
			? client.getServer().getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize().toString()
			: client != null && client.getCurrentServerEntry() != null ? client.getCurrentServerEntry().address : "unavailable";
		String dimension = world == null ? "unavailable" : client.world.getRegistryKey().getValue().toString();
		return new NativeActionRuntime.World(identity, dimension, Long.toString(loadGeneration), alive,
			ordinaryBusy.getAsBoolean() || !actuatorsFree(client), reflexOwns.getAsBoolean());
	}

	@Override public Window capture() {
		var client = MinecraftClient.getInstance();
		if (client == null || client.player == null || client.world == null) return unknown();
		var handler = client.player.currentScreenHandler;
		if (!(handler instanceof GenericContainerScreenHandler)) {
			current = null;
			return handler == client.player.playerScreenHandler
				? new Window(false, "", 0, List.of(), stack(client.player, -1, false, handler.getCursorStack())) : unknown();
		}
		if (current == null || current.clientHandler != handler || current.connection != client.getNetworkHandler()) {
			current = new Context(UUID.randomUUID().toString(), handler, client.getServer(), client.getNetworkHandler(),
				client.player.getUuid(), client.world.getRegistryKey().getValue().toString());
			contexts.put(current.id, current);
			while (contexts.size() > 256) contexts.remove(contexts.values().stream().filter(context -> context != retained).findFirst().orElseThrow().id);
		}
		return window(client.player, handler, current.id);
	}

	@Override public void retainWindow(String windowId) {
		Context context = contexts.get(windowId);
		if (context == null || (retained != null && retained != context)) throw new Rejected("container_changed");
		retained = context;
	}
	@Override public void releaseWindow(String windowId) {
		if (retained != null && retained.id.equals(windowId)) retained = null;
		contexts.remove(windowId);
	}

	@Override public CompletableFuture<Window> confirm(String windowId) {
		Context context = contexts.get(windowId);
		if (context == null || context.server == null) return CompletableFuture.failedFuture(new Rejected("confirmation_unavailable"));
		return context.server.submit(() -> {
			var player = context.server.getPlayerManager().getPlayer(context.playerId);
			// A detached, empty owned handler is release evidence even after the player's dimension changes.
			if (context.serverHandler != null && (player == null || player.currentScreenHandler != context.serverHandler)
				&& context.serverHandler.getCursorStack().isEmpty())
				return new Window(false, "", 0, List.of(), new Slot(-1, false, "", "", 0, 64));
			if (player == null || !player.getWorld().getRegistryKey().getValue().toString().equals(context.dimension)) return unknown();
			var handler = player.currentScreenHandler;
			if (handler == player.playerScreenHandler)
				return new Window(false, "", 0, List.of(), stack(player, -1, false, handler.getCursorStack()));
			if (handler.syncId != context.clientHandler.syncId || !(handler instanceof GenericContainerScreenHandler)) return unknown();
			if (context.serverHandler == null) context.serverHandler = handler;
			if (context.serverHandler != handler) return unknown();
			return window(player, handler, context.id);
		});
	}

	@Override public CompletableFuture<Window> click(Window expected, int slot, int button,
		NativeActionRuntime.EffectPermit permit, NativeActionRuntime.Permission permission) {
		var client = requireOwnedWindow(expected.windowId(), expected.syncId());
		if (!actuatorsFree(client)) throw new Rejected("controller_busy");
		Context context = contexts.get(expected.windowId());
		return context.server.submit(() -> {
			var player = context.server.getPlayerManager().getPlayer(context.playerId);
			permit.perform(permission, () -> {
				if (player == null || !player.isAlive() || player.isSpectator()
					|| !player.getWorld().getRegistryKey().getValue().toString().equals(context.dimension)) throw new Rejected("player_unavailable");
				var handler = player.currentScreenHandler;
				if (handler != context.serverHandler || handler.syncId != expected.syncId() || !handler.canUse(player)
					|| !handler.isValid(slot) || !expected.matches(window(player, handler, context.id))) throw new Rejected("container_changed");
				player.updateLastActionTime();
				// The same vanilla click routine as ServerPlayNetworkHandler.onClickSlot, without a check-to-packet race.
				handler.onSlotClick(slot, button, SlotActionType.PICKUP, player);
				handler.syncState();
			});
			return window(player, player.currentScreenHandler, context.id);
		});
	}

	@Override public CompletableFuture<Window> close(Window expected, NativeActionRuntime.EffectPermit permit, NativeActionRuntime.Permission permission) {
		requireOwnedWindow(expected.windowId(), expected.syncId());
		Context context = contexts.get(expected.windowId());
		return context.server.submit(() -> {
			var player = context.server.getPlayerManager().getPlayer(context.playerId);
			permit.perform(permission, () -> {
				if (player == null || player.currentScreenHandler != context.serverHandler
					|| !player.getWorld().getRegistryKey().getValue().toString().equals(context.dimension)) throw new Rejected("container_changed");
				if (!player.currentScreenHandler.getCursorStack().isEmpty()) throw new Rejected("cursor_not_empty");
				player.closeHandledScreen();
			});
			return new Window(false, "", 0, List.of(), stack(player, -1, false, player.currentScreenHandler.getCursorStack()));
		});
	}

	@Override public boolean controlsReleased() {
		var client = MinecraftClient.getInstance();
		return actuatorsFree(client) && client.currentScreen == null && !ordinaryBusy.getAsBoolean() && !reflexOwns.getAsBoolean();
	}

	private MinecraftClient requireOwnedWindow(String id, int syncId) {
		var client = MinecraftClient.getInstance();
		var context = contexts.get(id);
		if (client == null || client.player == null || client.interactionManager == null || context == null
			|| client.getNetworkHandler() != context.connection || client.player.currentScreenHandler != context.clientHandler
			|| client.player.currentScreenHandler.syncId != syncId) throw new Rejected("container_changed");
		return client;
	}

	private boolean actuatorsFree(MinecraftClient client) {
		if (client == null || client.player == null || client.player.isUsingItem() || client.player.fishHook != null) return false;
		if (camera.activeReason().isPresent()) return false;
		if (baritone != null && baritone.isLoaded() && (baritone.processActive() || baritone.cancellationPending())) return false;
		var keys = client.options;
		return !keys.forwardKey.isPressed() && !keys.backKey.isPressed() && !keys.leftKey.isPressed() && !keys.rightKey.isPressed()
			&& !keys.jumpKey.isPressed() && !keys.sneakKey.isPressed() && !keys.sprintKey.isPressed()
			&& !keys.attackKey.isPressed() && !keys.useKey.isPressed();
	}

	private static Window window(PlayerEntity player, ScreenHandler handler, String id) {
		var chest = (GenericContainerScreenHandler) handler;
		var slots = new ArrayList<Slot>();
		for (var slot : handler.slots) {
			boolean container = slot.id < chest.getRows() * 9;
			if (container || (slot.inventory == player.getInventory() && slot.getIndex() < 36))
				slots.add(stack(player, slot.id, container, slot.getStack()));
		}
		return new Window(true, id, handler.syncId, slots, stack(player, -1, false, handler.getCursorStack()));
	}

	private static Slot stack(PlayerEntity player, int slot, boolean container, ItemStack stack) {
		if (stack.isEmpty()) return new Slot(slot, container, "", "", 0, 64);
		var encoded = ItemStack.UNCOUNTED_CODEC.encodeStart(player.getWorld().getRegistryManager().getOps(JsonOps.INSTANCE), stack).getOrThrow();
		String variant = NativeActionRuntime.fingerprint("components", "item", encoded.getAsJsonObject());
		return new Slot(slot, container, Registries.ITEM.getId(stack.getItem()).toString(), variant, stack.getCount(), stack.getMaxCount());
	}

	private static Window unknown() { return new Window(true, "unknown", -1, List.of(), new Slot(-1, false, "", "", 0, 64)); }
	private static final class Context {
		final String id;
		final ScreenHandler clientHandler;
		final MinecraftServer server;
		final Object connection;
		final UUID playerId;
		final String dimension;
		ScreenHandler serverHandler; // Accessed only on the server thread, never as client-side world state.
		Context(String id, ScreenHandler clientHandler, MinecraftServer server, Object connection, UUID playerId, String dimension) {
			this.id = id; this.clientHandler = clientHandler; this.server = server; this.connection = connection;
			this.playerId = playerId; this.dimension = dimension;
		}
	}
}
