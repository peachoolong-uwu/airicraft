package ai.moeru.airicraft.agent.tasks;

import com.google.gson.JsonObject;

import java.util.Set;

/** One cast from the player's current position toward nearby water. */
public record FishOnceStepArgs(int x, int y, int z, int maxWaitTicks) {
	public FishOnceStepArgs {
		if (maxWaitTicks < 100 || maxWaitTicks > 2400) {
			throw new IllegalArgumentException("maxWaitTicks must be between 100 and 2400");
		}
	}

	public static FishOnceStepArgs parse(JsonObject args) {
		for (String key : args.keySet()) {
			if (!Set.of("x", "y", "z", "maxWaitTicks", "narration").contains(key)) {
				throw new IllegalArgumentException("unknown argument: " + key);
			}
		}
		return new FishOnceStepArgs(integer(args, "x"), integer(args, "y"), integer(args, "z"),
			args.has("maxWaitTicks") ? integer(args, "maxWaitTicks") : 1200);
	}

	private static int integer(JsonObject args, String key) {
		var value = args.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException(key + " must be an integer");
		}
		try {
			return value.getAsBigDecimal().intValueExact();
		}
		catch (ArithmeticException exception) {
			throw new IllegalArgumentException(key + " must be a 32-bit integer");
		}
	}
}
