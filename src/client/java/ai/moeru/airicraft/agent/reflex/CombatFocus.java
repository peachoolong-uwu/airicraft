package ai.moeru.airicraft.agent.reflex;

import java.util.Comparator;
import java.util.List;

/** Re-evaluate immediate danger each tick; movement and attacks share the winner. */
final class CombatFocus {
	record Candidate(String id, double distance, boolean visible, double closingSpeed, boolean ranged, boolean preparingAttack) {
		double priority() {
			// Contact is urgent even for a stationary mob. Approaching mobs become urgent sooner.
			double contactTicks = Math.max(0, distance - 2) / Math.max(.1, closingSpeed);
			return (distance <= 3 ? 4 : 0) + (ranged ? 0 : 10 / (1 + contactTicks))
				+ (preparingAttack ? 6 : 0) + 1 / (1 + distance);
		}
	}

	static String select(List<Candidate> candidates) {
		return candidates.stream().min(Comparator.comparing((Candidate c) -> !c.visible())
			.thenComparing(Comparator.comparingDouble(Candidate::priority).reversed())
			.thenComparing(Candidate::id)).map(Candidate::id).orElse(null);
	}
}
