package ai.moeru.airicraft.agent.reflex;

import java.util.Comparator;
import java.util.List;

/** Mob-specific attack focus. Emergency creeper evasion may temporarily override movement. */
final class CombatFocus {
	record Candidate(String id, double distance, boolean visible, double closingSpeed, boolean ranged, boolean preparingAttack, String entityTypeId, boolean baby) {
		Candidate(String id, double distance, boolean visible, double closingSpeed, boolean ranged, boolean preparingAttack) {
			this(id, distance, visible, closingSpeed, ranged, preparingAttack, "unknown", false);
		}
		double priority() {
			// Contact is urgent even for a stationary mob. Approaching mobs become urgent sooner.
			double contactTicks = Math.max(0, distance - 2) / Math.max(.1, closingSpeed);
			return (distance <= 3 ? 4 : 0) + (ranged ? 0 : 10 / (1 + contactTicks))
				+ (preparingAttack ? 6 : 0) + 1 / (1 + distance);
		}
	}

	static boolean meleePressure(List<Candidate> candidates) {
		return candidates.stream().anyMatch(c -> c.visible() && !c.ranged()
			&& (c.distance() <= 4 || c.distance() <= 6 && c.closingSpeed() > 0
				&& (c.distance() - 2) / c.closingSpeed() <= 10));
	}

	static int rank(Candidate c, boolean meleePressure) {
		return switch (c.entityTypeId()) {
			case "minecraft:witch" -> 5;
			case "minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager" -> c.baby() ? 4 : 1;
			case "minecraft:skeleton", "minecraft:stray", "minecraft:bogged" -> c.ranged() ? (meleePressure ? 0 : 3) : 1;
			case "minecraft:spider", "minecraft:cave_spider" -> 2;
			default -> 1;
		};
	}

	static String select(List<Candidate> candidates) {
		boolean pressure = meleePressure(candidates);
		return candidates.stream().min(Comparator.comparing((Candidate c) -> !c.visible())
			.thenComparing(Comparator.comparingInt((Candidate c) -> rank(c, pressure)).reversed())
			.thenComparing(Comparator.comparingDouble(Candidate::priority).reversed())
			.thenComparing(Candidate::id)).map(Candidate::id).orElse(null);
	}
}
