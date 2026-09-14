package ai.moeru.airicraft.agent.lighting;

import java.util.Locale;

public record LightingPolicy(
	boolean enabled,
	Mode mode,
	int maxLightLevel,
	boolean requireUnderground,
	int minSpacingBlocks,
	long revision
) {
	public LightingPolicy {
		// Retain the configuration field for callers, but automatic lighting
		// always excludes a sampling area with any sky access.
		requireUnderground = true;
		if (mode == null) {
			throw new IllegalArgumentException("mode is required");
		}
		if (maxLightLevel < 0 || maxLightLevel > 15) {
			throw new IllegalArgumentException("maxLightLevel must be between 0 and 15");
		}
		if (minSpacingBlocks < 1 || minSpacingBlocks > 16) {
			throw new IllegalArgumentException("minSpacingBlocks must be between 1 and 16");
		}
	}

	public static LightingPolicy defaults() {
		return new LightingPolicy(true, Mode.DARKNESS, 4, true, 6, 0L);
	}

	public static LightingPolicy disabled() {
		return new LightingPolicy(false, Mode.DARKNESS, 4, true, 6, 0L);
	}

	public enum Mode {
		DARKNESS,
		SPAWN_PROOF;

		public static Mode parse(String value) {
			return valueOf(value.strip().toUpperCase(Locale.ROOT));
		}

		public String wireName() {
			return name().toLowerCase(Locale.ROOT);
		}
	}
}
