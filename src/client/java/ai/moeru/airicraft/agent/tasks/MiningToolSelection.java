package ai.moeru.airicraft.agent.tasks;

import java.util.function.IntFunction;

/** Target-specific inventory selection; ties preserve the current tool. */
final class MiningToolSelection {
	private MiningToolSelection() {}

	static int preferredSlot(int selectedSlot, IntFunction<Score> scoreAt) {
		Score best = scoreAt.apply(selectedSlot);
		int bestSlot = best.eligible() ? selectedSlot : -1;
		for (int slot = 0; slot < 36; slot++) {
			Score candidate = scoreAt.apply(slot);
			if (candidate.eligible() && (!best.eligible() || candidate.speed() > best.speed())) {
				best = candidate;
				bestSlot = slot;
			}
		}
		return bestSlot;
	}

	static int preferredClearanceSlot(int selectedSlot, IntFunction<Score> scoreAt) {
		return preferredSlot(selectedSlot, slot -> {
			Score score = scoreAt.apply(slot);
			return new Score(true, clearanceSpeed(score));
		});
	}

	static float clearanceSpeed(Score score) {
		// Vanilla divides break progress by 30 when harvestable, otherwise by 100.
		return score.speed() * (score.eligible() ? 1.0F : 0.3F);
	}

	record Score(boolean eligible, float speed) {}
}
