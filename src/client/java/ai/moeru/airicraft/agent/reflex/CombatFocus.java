package ai.moeru.airicraft.agent.reflex;

import java.util.Comparator;
import java.util.List;

/** Finish one nearby visible opponent instead of switching on small distance changes. */
final class CombatFocus {
	record Candidate(String id, double distance, boolean visible) {}
	private String selected;

	String select(List<Candidate> candidates) {
		if (candidates.stream().anyMatch(c -> c.id().equals(selected) && c.visible() && c.distance() <= 6)) return selected;
		selected = candidates.stream().min(Comparator.comparing((Candidate c) -> !c.visible())
			.thenComparingDouble(Candidate::distance).thenComparing(Candidate::id)).map(Candidate::id).orElse(null);
		return selected;
	}

	void clear() { selected = null; }
}
