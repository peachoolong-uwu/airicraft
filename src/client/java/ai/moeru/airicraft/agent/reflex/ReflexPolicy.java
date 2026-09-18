package ai.moeru.airicraft.agent.reflex;

/** Session policy chosen by System 2; independent of the current safety episode. */
public record ReflexPolicy(boolean combatEnabled, boolean drowningEnabled,
	int maxThreatDistance, boolean requireLineOfSight) {
	private static final ReflexPolicy DEFAULT = new ReflexPolicy(true, true, 16, true);

	public ReflexPolicy {
		if (maxThreatDistance < 1 || maxThreatDistance > 32) {
			throw new IllegalArgumentException("maxThreatDistance must be between 1 and 32");
		}
	}

	public static ReflexPolicy defaults() { return DEFAULT; }

	public boolean acceptsMob(boolean ranged, double distance, boolean lineOfSight) {
		return combatEnabled && distance <= maxThreatDistance && (ranged || distance <= 6D)
			&& (!requireLineOfSight || lineOfSight);
	}

	/** Awareness is wider than the engagement gate, so a flanker is not forgotten outside melee range. */
	public boolean observesMob(double distance) {
		return combatEnabled && distance <= maxThreatDistance;
	}
}
