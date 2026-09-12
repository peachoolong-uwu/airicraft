package ai.moeru.airicraft.agent.lighting;

import ai.moeru.airicraft.agent.tasks.WorldTaskType;

public final class LightingPolicyEvaluator {
	private LightingPolicyEvaluator() {
	}

	public static boolean supportsActivity(WorldTaskType activity) {
		return activity == WorldTaskType.MINE || activity == WorldTaskType.NAVIGATE;
	}

	public static boolean shouldPlace(
		LightingPolicy policy,
		boolean supportedActivity,
		boolean torchAvailable,
		boolean skyVisible,
		int combinedLightLevel,
		int blockLightLevel,
		boolean nearbyTorch
	) {
		if (policy == null || !policy.enabled() || !supportedActivity || !torchAvailable || nearbyTorch) {
			return false;
		}
		if (policy.requireUnderground() && skyVisible) {
			return false;
		}
		int observedLight = policy.mode() == LightingPolicy.Mode.SPAWN_PROOF
			? blockLightLevel
			: combinedLightLevel;
		return observedLight <= policy.maxLightLevel();
	}
}
