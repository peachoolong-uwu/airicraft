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

	record Score(boolean eligible, float speed) {}
}
