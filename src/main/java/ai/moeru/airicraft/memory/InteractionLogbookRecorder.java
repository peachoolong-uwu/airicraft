package ai.moeru.airicraft.memory;

import net.minecraft.item.ItemStack;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.screen.*;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Server-thread observations, aggregated at the completed tick before asynchronous persistence. */
public final class InteractionLogbookRecorder {
	private static volatile java.util.function.BiConsumer<MinecraftServer,List<InteractionLogbook.Entry>> observer = (server, entries) -> { };
	public static void observe(java.util.function.BiConsumer<MinecraftServer,List<InteractionLogbook.Entry>> replacement) { observer = java.util.Objects.requireNonNull(replacement); }

	private static final Map<ScreenHandler, Context> CONTEXTS = new WeakHashMap<>();
	private static final Map<MinecraftServer, LinkedHashMap<Key, InteractionLogbook.Entry>> PENDING = new IdentityHashMap<>();
	private record Context(BlockPos position, String type) {}
	private record Key(String actor, String dimension, BlockPos position, String entityUuid, String action, String itemId) {}
	public record Click(Map<String, Integer> containerBefore, String craftItem, int craftCountBefore) {}

	public static void opened(ServerPlayerEntity player, BlockPos position, ScreenHandler handler) {
		if (!(handler instanceof GenericContainerScreenHandler || handler instanceof AbstractFurnaceScreenHandler
			|| handler instanceof CraftingScreenHandler)) return;
		String type = Registries.BLOCK.getId(player.getWorld().getBlockState(position).getBlock()).toString();
		CONTEXTS.put(handler, new Context(position.toImmutable(), type));
		if (containerSize(handler) > 0) add(player, handler, "container_observed", "", 0, contents(handler));
	}

	public static Click beforeClick(ServerPlayerEntity player, ScreenHandler handler, int slotId) {
		observeEntityContainer(player, handler);
		String craftItem = slotId >= 0 && slotId < handler.slots.size() && handler.getSlot(slotId) instanceof CraftingResultSlot
			? itemId(handler.getSlot(slotId).getStack()) : "";
		return new Click(contents(handler), craftItem, craftItem.isEmpty() ? 0 : carriedCount(player, handler, craftItem));
	}

	public static void afterClick(ServerPlayerEntity player, ScreenHandler handler, Click before) {
		Map<String, Integer> after = contents(handler);
		Set<String> items = new TreeSet<>(before.containerBefore().keySet());
		items.addAll(after.keySet());
		boolean changed = false;
		for (String item : items) {
			int delta = after.getOrDefault(item, 0) - before.containerBefore().getOrDefault(item, 0);
			if (delta == 0) continue;
			changed = true;
			// A signed change coalesces pickup/return clicks within this same server tick.
			add(player, handler, "container_delta", item, delta, Map.of());
		}
		if (changed) add(player, handler, "container_observed", "", 0, after);
		if (!before.craftItem().isEmpty()) {
			int gained = carriedCount(player, handler, before.craftItem()) - before.craftCountBefore();
			if (gained > 0) add(player, handler, "crafted", before.craftItem(), gained, Map.of());
		}
	}

	public static void dropped(ServerPlayerEntity player, ItemStack stack) {
		if (!stack.isEmpty()) add(player, null, "dropped", itemId(stack), stack.getCount(), Map.of());
	}

	private static int carriedCount(ServerPlayerEntity player, ScreenHandler handler, String item) {
		int total = itemId(handler.getCursorStack()).equals(item) ? handler.getCursorStack().getCount() : 0;
		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack stack = player.getInventory().getStack(i);
			if (itemId(stack).equals(item)) total += stack.getCount();
		}
		return total;
	}

	private static int containerSize(ScreenHandler handler) {
		if (handler instanceof GenericContainerScreenHandler chest) return chest.getRows() * 9;
		return handler instanceof AbstractFurnaceScreenHandler ? 3 : 0;
	}

	private static Map<String, Integer> contents(ScreenHandler handler) {
		Map<String, Integer> result = new TreeMap<>();
		for (int i = 0; i < containerSize(handler); i++) {
			ItemStack stack = handler.getSlot(i).getStack();
			if (!stack.isEmpty()) result.merge(itemId(stack), stack.getCount(), Integer::sum);
		}
		return result;
	}

	private static String itemId(ItemStack stack) { return stack.isEmpty() ? "" : Registries.ITEM.getId(stack.getItem()).toString(); }

	/** Only inspect the inventory the server has already opened for this player. */
	private static Entity containerEntity(ScreenHandler handler) {
		return handler instanceof GenericContainerScreenHandler container && container.getInventory() instanceof Entity entity ? entity : null;
	}

	private static void observeEntityContainer(ServerPlayerEntity player, ScreenHandler handler) {
		Entity entity = containerEntity(handler);
		if (entity == null || CONTEXTS.containsKey(handler)) return;
		CONTEXTS.put(handler, new Context(entity.getBlockPos(), Registries.ENTITY_TYPE.getId(entity.getType()).toString()));
		add(player, handler, "container_observed", "", 0, contents(handler));
	}

	private static void add(ServerPlayerEntity player, ScreenHandler handler, String action, String item, int count, Map<String, Integer> contents) {
		Context context = handler == null ? null : CONTEXTS.get(handler);
		Entity entity = containerEntity(handler);
		BlockPos position = entity != null ? entity.getBlockPos() : context == null ? player.getBlockPos() : context.position();
		String entityUuid = entity == null ? "" : entity.getUuidAsString();
		String dimension = player.getWorld().getRegistryKey().getValue().toString();
		Key key = new Key(player.getUuidAsString(), dimension, position, entityUuid, action, item);
		var pending = PENDING.computeIfAbsent(player.getServer(), ignored -> new LinkedHashMap<>());
		InteractionLogbook.Entry previous = pending.get(key);
		int amount = action.equals("container_observed") ? 0 : count + (previous == null ? 0 : previous.count());
		pending.put(key, new InteractionLogbook.Entry(System.currentTimeMillis(), player.getWorld().getTime(),
			player.getUuidAsString(), dimension, position.getX(), position.getY(), position.getZ(), action, item, amount,
			context == null ? "" : context.type(), contents, entityUuid));
	}

	public static void flushTick(MinecraftServer server) {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList())
			observeEntityContainer(player, player.currentScreenHandler);
		var entries = PENDING.remove(server);
		if (entries == null) return;
		List<InteractionLogbook.Entry> batch = new ArrayList<>();
		for (var entry : entries.values()) {
			if (entry.action().equals("container_delta")) {
				if (entry.count() == 0) continue;
				batch.add(new InteractionLogbook.Entry(entry.timestampMs(), entry.worldTick(), entry.actor(), entry.dimension(),
					entry.x(), entry.y(), entry.z(), entry.count() > 0 ? "container_put" : "container_take",
					entry.itemId(), Math.abs(entry.count()), entry.containerType(), Map.of(), entry.containerEntityUuid()));
			} else batch.add(entry);
		}
		InteractionLogbook.record(server.getSavePath(WorldSavePath.ROOT), batch);
		observer.accept(server, List.copyOf(batch));
	}
}
