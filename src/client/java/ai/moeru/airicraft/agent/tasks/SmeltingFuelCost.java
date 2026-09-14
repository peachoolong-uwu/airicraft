package ai.moeru.airicraft.agent.tasks;

/** Fuel committed to a batch, not merely the number of inventory items consumed. */
public final class SmeltingFuelCost {
	private SmeltingFuelCost() {}

	public static int compare(String leftId, int leftQuantity, int leftTicks,
		String rightId, int rightQuantity, int rightTicks) {
		int burn = Long.compare((long) leftQuantity * leftTicks, (long) rightQuantity * rightTicks);
		if (burn != 0) return burn;
		// A whole log can instead become four planks with the same per-item burn time.
		int logs = Boolean.compare(wholeLog(leftId), wholeLog(rightId));
		return logs;
	}

	private static boolean wholeLog(String id) {
		return id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem") || id.endsWith("_hyphae")
			|| id.equals("minecraft:bamboo_block") || id.equals("minecraft:stripped_bamboo_block");
	}
}
