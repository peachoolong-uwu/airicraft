package ai.moeru.airicraft.agent.reflex;

import net.minecraft.util.math.Vec3d;
import java.util.Map;

/** Detect a motionless distant encounter; retain its identity while the planner chooses a tactic. */
record CombatStalemate(Phase phase, long sinceTick, Vec3d playerPosition, Map<String, Vec3d> threats) {
	static final int STALLED_TICKS = 400;
	enum Phase { APPROACHING, DEFERRED }

	CombatStalemate {
		threats = Map.copyOf(threats);
	}

	static CombatStalemate observe(CombatStalemate previous, long tick, Vec3d playerPosition,
		Map<String, Vec3d> threats, boolean immediateDanger) {
		if (immediateDanger || threats.isEmpty()) return null;
		boolean changedThreat = previous == null || !previous.threats.keySet().equals(threats.keySet())
			|| threats.entrySet().stream().anyMatch(entry -> entry.getValue().squaredDistanceTo(previous.threats.get(entry.getKey())) >= 1D);
		if (changedThreat || previous.phase == Phase.APPROACHING && playerPosition.squaredDistanceTo(previous.playerPosition) >= 1D) {
			return new CombatStalemate(Phase.APPROACHING, tick, playerPosition, threats);
		}
		if (previous.phase == Phase.DEFERRED) return previous;
		return tick - previous.sinceTick >= STALLED_TICKS
			? new CombatStalemate(Phase.DEFERRED, previous.sinceTick, playerPosition, threats) : previous;
	}

	boolean deferred() { return phase == Phase.DEFERRED; }
}
