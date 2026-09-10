package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

final class PlacementSneakController {
	private boolean ownsSneakKey;

	Preparation prepare(MinecraftClient client, ClientPlayerEntity player) {
		Preparation preparation = preparation(ownsSneakKey, client.options.sneakKey.isPressed(), player.isSneaking());
		if (preparation == Preparation.PRESS_AND_WAIT) {
			client.options.sneakKey.setPressed(true);
			ownsSneakKey = true;
		}
		return preparation;
	}

	void release(MinecraftClient client) {
		if (ownsSneakKey && client != null) {
			client.options.sneakKey.setPressed(false);
		}
		ownsSneakKey = false;
	}

	static Preparation preparation(boolean ownsSneakKey, boolean sneakKeyPressed, boolean playerSneaking) {
		// Navigation release can clear keys after placement first presses them.
		if (ownsSneakKey && !sneakKeyPressed) {
			return Preparation.PRESS_AND_WAIT;
		}
		if (playerSneaking) {
			return Preparation.READY;
		}
		if (ownsSneakKey || sneakKeyPressed) {
			return Preparation.WAITING;
		}
		return Preparation.PRESS_AND_WAIT;
	}

	enum Preparation {
		READY,
		PRESS_AND_WAIT,
		WAITING
	}
}
