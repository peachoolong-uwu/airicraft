package ai.moeru.airicraft.agent.tasks;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;
import java.util.Set;

/** Called by the current breaking owner, never by a background inventory task. */
public final class MiningToolPreparation {
	private MiningToolPreparation() {}

	public static Result ensureSelected(MinecraftClient client, ClientPlayerEntity player, List<BlockState> targets) {
		return ensureSelected(client, player, targets, List.of());
	}

	public static Result ensureSelected(
		MinecraftClient client, ClientPlayerEntity player, List<BlockState> targets, List<String> requiredToolItemIds
	) {
		if (player.currentScreenHandler != player.playerScreenHandler
			|| !player.currentScreenHandler.getCursorStack().isEmpty() || player.isUsingItem()) {
			return Result.failed("inventory_unavailable_for_tool_selection");
		}
		Set<String> required = requiredToolItemIds == null ? Set.of() : Set.copyOf(requiredToolItemIds);
		var inventory = player.getInventory();
		int selectedSlot = inventory.getSelectedSlot();
		int sourceSlot = MiningToolSelection.preferredSlot(selectedSlot,
			slot -> score(inventory.getStack(slot), targets, required));
		if (sourceSlot < 0) {
			return Result.failed(required.isEmpty()
				? "missing_suitable_tool blockIds=" + blockIds(targets)
				: "missing_required_harvest_tool itemIds=" + required);
		}
		MiningToolSelection.Score expected = score(inventory.getStack(sourceSlot), targets, required);
		if (sourceSlot != selectedSlot) {
			if (sourceSlot < 9) {
				selectedSlot = sourceSlot;
				inventory.setSelectedSlot(selectedSlot);
			} else {
				// Main inventory indices 9..35 equal the player screen's slot IDs.
				client.interactionManager.clickSlot(player.playerScreenHandler.syncId, sourceSlot,
					selectedSlot, SlotActionType.SWAP, player);
			}
			if (client.getNetworkHandler() != null) {
				client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(selectedSlot));
			}
		}
		MiningToolSelection.Score actual = score(inventory.getSelectedStack(), targets, required);
		return actual.eligible() && actual.speed() >= expected.speed()
			? Result.success() : Result.failed("tool_selection_failed blockIds=" + blockIds(targets));
	}

	private static MiningToolSelection.Score score(ItemStack stack, List<BlockState> targets, Set<String> required) {
		boolean eligible = required.isEmpty() || required.contains(Registries.ITEM.getId(stack.getItem()).toString());
		float speed = Float.MAX_VALUE;
		for (BlockState state : targets) {
			eligible &= !state.isToolRequired() || !stack.isEmpty() && stack.isSuitableFor(state);
			speed = Math.min(speed, stack.isEmpty() ? 1.0F : stack.getMiningSpeedMultiplier(state));
		}
		return new MiningToolSelection.Score(eligible, speed == Float.MAX_VALUE ? 1.0F : speed);
	}

	private static List<String> blockIds(List<BlockState> targets) {
		return targets.stream().map(state -> Registries.BLOCK.getId(state.getBlock()).toString()).distinct().toList();
	}

	public record Result(boolean ok, String message) {
		static Result success() { return new Result(true, ""); }
		static Result failed(String message) { return new Result(false, message); }
	}
}
