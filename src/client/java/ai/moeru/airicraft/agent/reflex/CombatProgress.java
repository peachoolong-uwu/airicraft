package ai.moeru.airicraft.agent.reflex;

import java.util.LinkedHashMap;
import java.util.Map;

/** Observation only: damage received and attempted inputs are not tactical progress. */
record CombatProgress(long lastProgressTick, Map<String, Target> best, boolean stalled) {
    static final int STALLED_TICKS = 200;
    record Target(double distance, float health) { }

    CombatProgress { best = Map.copyOf(best); }

    static CombatProgress observe(CombatProgress previous, long tick, Map<String, Target> current) {
        return observe(previous, tick, current, false);
    }

    static CombatProgress observe(CombatProgress previous, long tick, Map<String, Target> current, boolean defeatedTarget) {
        if (current.isEmpty()) return null;
        if (previous == null) return new CombatProgress(tick, current, false);
        boolean progressed = defeatedTarget;
        var best = new LinkedHashMap<String, Target>();
        for (var entry : current.entrySet()) {
            Target now = entry.getValue(), old = previous.best.get(entry.getKey());
            if (old == null) {
                // A new wave or target switch is not itself progress.
                best.put(entry.getKey(), now);
                continue;
            }
            boolean closer = now.distance() <= old.distance() - 1;
            boolean damaged = now.health() <= old.health() - 1;
            progressed |= closer || damaged;
            best.put(entry.getKey(), new Target(closer ? now.distance() : old.distance(),
                damaged ? now.health() : old.health()));
        }
        long last = progressed ? tick : previous.lastProgressTick;
        return new CombatProgress(last, best, tick - last >= STALLED_TICKS);
    }
}
