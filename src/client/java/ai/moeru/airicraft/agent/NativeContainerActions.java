package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.os.NativeActionRuntime;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

import static ai.moeru.airicraft.os.NativeActionRuntime.*;

/** Bounded ordinary clicks, with a fresh server observation between every click. */
public final class NativeContainerActions implements NativeActionRuntime.Port {
	private static final Gson JSON = new Gson();
	private static final long CONFIRMATION_TIMEOUT_NANOS = 30_000_000_000L;
	// These reference-world items use ordinary stack clicks. Bundle/container click hooks are not supported.
	private static final Set<String> SUPPORTED_ITEMS = java.util.stream.Stream.concat(
		java.util.stream.Stream.of("wheat", "wheat_seeds", "potato", "carrot", "birch_sapling", "birch_log", "birch_planks",
			"oak_planks", "stick", "string", "bone", "bone_meal", "cod", "salmon", "pufferfish", "tropical_fish", "ink_sac",
			"mutton", "cooked_mutton", "cooked_cod", "cooked_salmon", "bread", "baked_potato", "poisonous_potato",
			"shears", "iron_axe", "iron_hoe", "iron_shovel", "iron_pickaxe", "stone_axe", "stone_hoe", "wooden_axe", "wooden_hoe",
			"fishing_rod", "water_bucket", "bucket", "crafting_table", "leather", "leather_boots", "bowl", "lily_pad",
			"rotten_flesh", "tripwire_hook", "potion", "enchanted_book", "name_tag", "saddle", "nautilus_shell", "bow"),
		java.util.stream.Stream.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
			"cyan", "purple", "blue", "brown", "green", "red", "black").map(color -> color + "_wool"))
		.map(item -> "minecraft:" + item).collect(java.util.stream.Collectors.toUnmodifiableSet());
	private final Access access;
	private final LongSupplier clock;

	public NativeContainerActions(Access access, LongSupplier clock) {
		this.access = access;
		this.clock = clock;
	}

	@Override public World world() { return access.world(); }
	@Override public void availability(AvailabilityGate gate) {
		access.availability(gate);
	}
	@Override public Set<String> operations() { return access.available() ? Set.of("transfer_container", "retain_container") : Set.of(); }
	@Override public JsonObject observe() {
		return observe(new JsonObject());
	}
	@Override public JsonObject observe(JsonObject query) {
		if (!Set.of("progressScopes").containsAll(query.keySet()) || query.has("progressScopes") && !query.get("progressScopes").isJsonArray())
			throw new Rejected("invalid_progress_scopes");
		var result = new JsonObject();
		result.add("progress", access.progress(query.has("progressScopes") ? query.getAsJsonArray("progressScopes") : null));
		var window = access.capture();
		result.add("window", JSON.toJsonTree(window));
		var inventory = access.inventory();
		result.add("inventory", JSON.toJsonTree(Map.of("available", inventory != null, "slots", inventory == null ? List.of() : inventory)));
		result.add("availability", access.availabilityProof(window, inventory));
		result.add("scope", JSON.toJsonTree(Map.of("kind", "current_container", "windowId", window.windowId(), "syncId", window.syncId())));
		result.add("coverage", JSON.toJsonTree(Map.of("state", window.syncId() < 0 ? "unknown" : "known",
			"source", "client_open_handler", "truncated", false, "unopenedContainers", "unknown")));
		result.add("operations", JSON.toJsonTree(operations()));
		result.add("supportedItems", JSON.toJsonTree(SUPPORTED_ITEMS.stream().sorted().toList()));
		return result;
	}
	@Override public Operation prepare(String name, JsonObject args, JsonObject observation, EffectPermit permit) {
		if (name.equals("retain_container")) {
			if (!args.keySet().equals(Set.of("windowId", "syncId"))) throw new Rejected("invalid_context_request");
			Window basis = requireBoundWindow(args, observation).observed();
			return new RetainedWindow(new Window(true, basis.windowId(), basis.syncId(), List.of(), basis.cursor()), permit);
		}
		if (!name.equals("transfer_container")) throw new Rejected("operation_not_granted");
		return prepareTransfer(args, observation, permit, WindowUse.STANDALONE);
	}
	private Operation prepareTransfer(JsonObject args, JsonObject observation, EffectPermit permit, WindowUse use) {
		if (!args.keySet().equals(Set.of("windowId", "syncId", "direction", "itemId", "quantity", "allowance")))
			throw new Rejected("invalid_transfer_request");
		String direction = string(args, "direction");
		String itemId = string(args, "itemId");
		if (!SUPPORTED_ITEMS.contains(itemId)) throw new Rejected("unsupported_transfer_item");
		int quantity = integer(args, "quantity", 1, 64);
		if (!Set.of("deposit", "withdraw").contains(direction) || !args.get("allowance").isJsonObject())
			throw new Rejected("invalid_transfer_request");
		JsonObject allowance = args.getAsJsonObject("allowance");
		if (!allowance.keySet().equals(Set.of("sourceItems", "destinationItems"))) throw new Rejected("invalid_transfer_request");
		if (integer(allowance, "sourceItems", 1, 64) < quantity || integer(allowance, "destinationItems", 1, 64) < quantity)
			throw new Rejected("allowance_exceeded");
		var windows = requireBoundWindow(args, observation);
		Window basis = windows.observed();
		var moves = ContainerInventoryController.plan(basis.slots().stream().map(slot ->
			new ContainerInventoryController.Slot(slot.id(), slot.container(), slot.itemId(), slot.variant(), slot.count(), slot.maxCount())).toList(),
			direction, itemId, quantity);
		var affected = moves.stream().flatMap(move -> java.util.stream.Stream.of(move.source(), move.target())).collect(java.util.stream.Collectors.toSet());
		var guarded = new Window(true, basis.windowId(), basis.syncId(), basis.slots().stream().filter(slot -> affected.contains(slot.id())).toList(), basis.cursor());
		if (!guarded.matches(windows.current())) throw new Rejected("container_changed");
		return new Transfer(guarded, moves, quantity, permit, use);
	}
	private BoundWindow requireBoundWindow(JsonObject args, JsonObject observation) {
		String windowId = string(args, "windowId");
		int syncId = integer(args, "syncId", 0, Integer.MAX_VALUE);
		Window basis = JSON.fromJson(observation.get("window"), Window.class);
		Window current = access.capture();
		if (!basis.open() || !current.open() || !basis.windowId().equals(windowId) || !current.windowId().equals(windowId)
			|| basis.syncId() != syncId || current.syncId() != syncId) throw new Rejected("container_changed");
		if (basis.cursor().count() != 0 || current.cursor().count() != 0) throw new Rejected("cursor_not_empty");
		return new BoundWindow(basis, current);
	}
	private record BoundWindow(Window observed, Window current) {}

	private enum WindowUse { STANDALONE, CONTEXT_STEP, CONTEXT_CLEANUP }

	private final class RetainedWindow implements ContextOperation {
		private final Window binding;
		private final EffectPermit permit;
		private CompletableFuture<Window> pending;
		private boolean retained;
		private boolean ready;
		private Transfer cleanup;
		private long phaseSince;

		private RetainedWindow(Window binding, EffectPermit permit) {
			this.binding = binding; this.permit = permit; phaseSince = clock.getAsLong();
		}
		@Override public boolean ready() { return ready; }
		@Override public Operation prepare(String name, JsonObject args, JsonObject observation, EffectPermit stepPermit) {
			if (!ready || !name.equals("transfer_container")) throw new Rejected("context_operation_not_granted");
			if (!binding.windowId().equals(string(args, "windowId")) || binding.syncId() != integer(args, "syncId", 0, Integer.MAX_VALUE))
				throw new Rejected("container_changed");
			return prepareTransfer(args, observation, stepPermit, WindowUse.CONTEXT_STEP);
		}
		@Override public Progress tick(Permission permission) {
			if (permission == Permission.OBSERVE) {
				if (!ready && clock.getAsLong() - phaseSince >= CONFIRMATION_TIMEOUT_NANOS)
					return progress(State.FAILED, "awaiting_handback", cleanup == null ? "context_entry_timeout" : "context_exit_timeout");
				return progress(State.RECONCILING, "awaiting_handback", "");
			}
			if (!retained) { access.retainWindow(binding.windowId()); retained = true; }
			if (permission == Permission.CLEANUP && cleanup == null) {
				ready = false; phaseSince = clock.getAsLong();
				cleanup = new Transfer(binding, List.of(), 0, permit, WindowUse.CONTEXT_CLEANUP);
			}
			if (cleanup != null) {
				var result = cleanup.tick(permission);
				// Retention/closure perform no item clicks. Child inventory uncertainty stays on its own receipt.
				var effects = Map.<String, Object>of("windowId", binding.windowId(), "accountingComplete", true,
					"releaseEvidence", result.effects().get("releaseEvidence"));
				if (!result.released() && clock.getAsLong() - phaseSince >= CONFIRMATION_TIMEOUT_NANOS)
					return new Progress(State.FAILED, false, result.phase(), "context_exit_timeout", effects);
				return new Progress(result.state(), result.released(), result.phase(), result.reason(), effects);
			}
			if (ready) {
				if (!binding.matches(access.capture()) || !access.contextControlsReady(binding.windowId())) {
					ready = false;
					return progress(State.FAILED, "context_changed", "context_changed");
				}
				return progress(State.RUNNING, "context_ready", "");
			}
			if (pending == null) pending = access.confirm(binding.windowId());
			if (pending.isDone()) {
				Window confirmed = pending.join();
				pending = null;
				if (binding.matches(confirmed) && binding.matches(access.capture()) && access.contextControlsReady(binding.windowId())) {
					ready = true;
					return progress(State.RUNNING, "context_ready", "");
				}
			}
			if (clock.getAsLong() - phaseSince >= CONFIRMATION_TIMEOUT_NANOS)
				return progress(State.FAILED, "verifying_context", "context_entry_timeout");
			return progress(State.RUNNING, "verifying_context", "");
		}
		private Progress progress(State state, String phase, String reason) {
			return new Progress(state, false, phase, reason, Map.of("windowId", binding.windowId(), "accountingComplete", true,
				"releaseEvidence", Map.of("verified", false)));
		}
	}

	private static String string(JsonObject args, String key) {
		var value = args.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
			|| value.getAsString().isBlank() || value.getAsString().length() > 256) throw new Rejected("invalid_transfer_request");
		return value.getAsString();
	}
	private static int integer(JsonObject args, String key, int minimum, int maximum) {
		try {
			var value = args.get(key);
			if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new Rejected("invalid_transfer_request");
			int number = value.getAsBigDecimal().intValueExact();
			if (number < minimum || number > maximum) throw new Rejected("invalid_transfer_request");
			return number;
		} catch (ArithmeticException exception) { throw new Rejected("invalid_transfer_request"); }
	}

	private final class Transfer implements Operation {
		private final String windowId;
		private final int syncId;
		private final int quantity;
		private final List<Click> clicks = new ArrayList<>();
		private Window expected;
		private CompletableFuture<Window> pending;
		private int nextClick;
		private int pendingCredit;
		private int transferred;
		private boolean closing;
		private int returnSlot = -1;
		private final EffectPermit permit;
		private Window beforePending;
		private boolean serverConfirmed;
		private boolean accountingComplete = true;
		private Map<String, Object> releaseEvidence = Map.of("verified", false);
		private long waitSince;
		private final WindowUse use;

		private Transfer(Window initial, List<ContainerInventoryController.Move> moves, int quantity, EffectPermit permit, WindowUse use) {
			windowId = initial.windowId();
			syncId = initial.syncId();
			this.quantity = quantity;
			this.permit = permit;
			this.use = use;
			expected = initial;
			Window planned = initial;
			for (var move : moves) {
				planned = addClick(planned, move.source(), 0, 0);
				for (int i = 0; i < move.count(); i++) planned = addClick(planned, move.target(), 1, 1);
				if (planned.cursor().count() > 0) planned = addClick(planned, move.source(), 0, 0);
			}
			if (use == WindowUse.STANDALONE) access.retainWindow(windowId);
		}
		private Window addClick(Window before, int slot, int button, int credit) {
			Window after = clicked(before, slot, button);
			clicks.add(new Click(slot, button, after, credit));
			return after;
		}

		@Override public Progress tick(Permission permission) {
			if (permission == Permission.OBSERVE) return progress(State.RECONCILING, false, "awaiting_handback");
			if (pending == null) {
				waitSince = clock.getAsLong();
				pending = access.confirm(windowId);
				return progress(State.RUNNING, false, "verifying_source");
			}
			if (!pending.isDone()) return waiting("awaiting_server");
			Window confirmed;
			try { confirmed = pending.join(); }
			catch (java.util.concurrent.CompletionException exception) {
				pending = null;
				nextClick = clicks.size();
				if (exception.getCause() instanceof Rejected rejected) {
					if (beforePending != null) expected = beforePending;
					pendingCredit = 0;
					closing = false;
					return new Progress(State.FAILED, false, "cleanup_required", rejected.code(), progress(State.FAILED, false, "").effects());
				}
				throw exception;
			}
			if (closing || !confirmed.open()) {
				if (!closing) accountingComplete = false;
				Window client = access.capture();
				boolean controlsFree = access.controlsReleased();
				boolean released = !confirmed.open() && confirmed.cursor().count() == 0
					&& !client.open() && client.cursor().count() == 0 && controlsFree;
				releaseEvidence = Map.of("verified", released, "source", "server_handler_and_client_controls", "windowId", windowId,
					"serverWindowClosed", !confirmed.open(), "clientWindowClosed", !client.open(),
					"cursorEmpty", confirmed.cursor().count() == 0 && client.cursor().count() == 0, "controlsFree", controlsFree);
				if (!released) {
					pending = access.confirm(windowId);
					return waiting("verifying_close");
				}
				if (use != WindowUse.CONTEXT_STEP) access.releaseWindow(windowId);
				return closing ? progress(State.SUCCEEDED, true, "released")
					: new Progress(State.FAILED, true, "released", "container_closed", progress(State.FAILED, true, "released").effects());
			}
			if (!expected.matches(confirmed)) {
				pending = access.confirm(windowId);
				return waiting("awaiting_server");
			}
			transferred += pendingCredit;
			pendingCredit = 0;
			serverConfirmed = true;
			accountingComplete = true;
			if (!expected.matches(access.capture())) {
				pending = access.confirm(windowId);
				return waiting("awaiting_client_sync");
			}
			if (permission == Permission.CLEANUP) {
				nextClick = clicks.size();
				if (expected.cursor().count() > 0) {
					Window after = clicked(expected, returnSlot, 0);
					beforePending = expected;
					waitSince = clock.getAsLong();
					accountingComplete = false;
					pending = access.click(expected, returnSlot, 0, permit, permission);
					expected = after;
					return progress(State.RECONCILING, false, "returning_cursor");
				}
			}
			if (nextClick == clicks.size()) {
				if (use == WindowUse.CONTEXT_STEP) {
					boolean released = expected.cursor().count() == 0 && access.contextControlsReady(windowId);
					releaseEvidence = Map.of("verified", released, "source", "server_handler_and_context", "windowId", windowId,
						"windowRetained", true, "cursorEmpty", expected.cursor().count() == 0, "controlsFree", released);
					return released ? progress(State.SUCCEEDED, true, "context_boundary") : waiting("verifying_context_boundary");
				}
				beforePending = expected;
				waitSince = clock.getAsLong();
				pending = access.close(expected, permit, permission);
				closing = true;
				return progress(State.RUNNING, false, "closing");
			}
			Click click = clicks.get(nextClick++);
			if (expected.cursor().count() == 0) returnSlot = click.slot();
			beforePending = expected;
			waitSince = clock.getAsLong();
			accountingComplete = false;
			pending = access.click(expected, click.slot(), click.button(), permit, permission);
			expected = click.after();
			pendingCredit = click.credit();
			return progress(State.RUNNING, false, "verifying_click");
		}

		private Progress waiting(String phase) {
			if (clock.getAsLong() - waitSince >= CONFIRMATION_TIMEOUT_NANOS)
				return new Progress(State.FAILED, false, phase, "confirmation_timeout", progress(State.FAILED, false, phase).effects());
			return progress(State.RECONCILING, false, phase);
		}

		private Progress progress(State state, boolean released, String phase) {
			return new Progress(state, released, phase, "", Map.of("windowId", windowId, "quantity", quantity,
				"transferred", transferred, "remaining", quantity - transferred, "serverConfirmed", serverConfirmed,
				"accountingComplete", accountingComplete, "releaseEvidence", releaseEvidence));
		}
	}

	private static Window clicked(Window before, int slotId, int button) {
		var slots = new ArrayList<>(before.slots());
		int index = -1;
		for (int i = 0; i < slots.size(); i++) if (slots.get(i).id() == slotId) index = i;
		if (index < 0) throw new Rejected("slot_unknown");
		Slot target = slots.get(index);
		Slot cursor = before.cursor();
		if (button == 0) {
			slots.set(index, target.withStack(cursor, cursor.count()));
			cursor = cursor.withStack(target, target.count());
		} else {
			if (cursor.count() < 1 || target.count() >= target.maxCount()
				|| (target.count() > 0 && (!target.itemId().equals(cursor.itemId()) || !target.variant().equals(cursor.variant()))))
				throw new Rejected("slot_changed");
			slots.set(index, target.withStack(cursor, target.count() + 1));
			cursor = cursor.withStack(cursor, cursor.count() - 1);
		}
		return new Window(true, before.windowId(), before.syncId(), slots, cursor);
	}

	private record Click(int slot, int button, Window after, int credit) {}
	public record Slot(int id, boolean container, String itemId, String variant, int count, int maxCount) {
		private Slot withStack(Slot stack, int quantity) {
			return new Slot(id, container, quantity == 0 ? "" : stack.itemId(), quantity == 0 ? "" : stack.variant(), quantity, quantity == 0 ? 64 : stack.maxCount());
		}
	}
	public record Window(boolean open, String windowId, int syncId, List<Slot> slots, Slot cursor) {
		public Window { slots = List.copyOf(slots); }
		/** Match this request's affected slots, allowing unrelated inventory changes. */
		public boolean matches(Window actual) {
			return open == actual.open() && windowId.equals(actual.windowId()) && syncId == actual.syncId()
				&& cursor.equals(actual.cursor()) && actual.slots().containsAll(slots);
		}
	}
	/** Minecraft is the external system. Implementations use its owning threads and ordinary GUI clicks. */
	public interface Access {
		default boolean available() { return true; }
		default void availability(AvailabilityGate gate) {}
		default JsonObject availabilityProof(Window window, List<Slot> inventory) {
			var result = new JsonObject(); result.addProperty("available", false); return result;
		}
		default void retainWindow(String windowId) {}
		default void releaseWindow(String windowId) {}
		/** Main inventory and hotbar, independent of an open handler; null means unavailable. */
		default List<Slot> inventory() { return null; }
		default JsonObject progress(com.google.gson.JsonArray scopes) {
			if (scopes != null) ai.moeru.airicraft.os.NativeProgressClocks.parse(scopes, world().dimension());
			var result = new JsonObject(); result.addProperty("available", false); result.add("scopes", new com.google.gson.JsonArray()); return result;
		}
		World world();
		Window capture();
		CompletableFuture<Window> confirm(String windowId);
		CompletableFuture<Window> click(Window expected, int slot, int button, EffectPermit permit, Permission permission);
		CompletableFuture<Window> close(Window expected, EffectPermit permit, Permission permission);
		boolean controlsReleased();
		/** Quiescent controls may include this context's own visible container screen. */
		default boolean contextControlsReady(String windowId) { return controlsReleased(); }
	}
}
