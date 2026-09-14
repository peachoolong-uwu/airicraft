package ai.moeru.airicraft.agent.llm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Deterministic, model-facing descriptions. Native observations remain structured. */
public final class PlannerStateText {
	private static final List<String> ORDINALS = List.of("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth");
	private static final List<String> ARMOR = List.of("head", "chest", "legs", "feet");

	private PlannerStateText() {}

	public static String item(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}

	public static String inventory(Map<String, Integer> counts) {
		if (counts.isEmpty()) return "Inventory empty.";
		return "Carrying " + new TreeMap<>(counts).entrySet().stream()
			.map(entry -> stack(entry.getKey(), entry.getValue())).collect(Collectors.joining(", ")) + ".";
	}

	public record HotbarSlot(int index, String itemId, int count) {
		public HotbarSlot {
			if (index < 0 || index >= 9 || count < 0) throw new IllegalArgumentException("Invalid hotbar slot");
		}
	}

	/** Adapter for WorldEvidence's existing slot=id x count encoding (without spaces). */
	public static List<HotbarSlot> hotbarEvidence(List<String> entries) {
		return entries.stream().map(entry -> {
			int equals = entry.indexOf('=');
			int index = Integer.parseInt(entry.substring(0, equals));
			String value = entry.substring(equals + 1);
			if (value.equals("empty")) return new HotbarSlot(index, "minecraft:air", 0);
			int countStart = value.lastIndexOf('x');
			return new HotbarSlot(index, value.substring(0, countStart), Integer.parseInt(value.substring(countStart + 1)));
		}).toList();
	}

	public static String hotbar(List<HotbarSlot> slots, int selected) {
		boolean complete = slots.stream().map(HotbarSlot::index).distinct().count() == 9;
		var occupied = slots.stream().filter(slot -> slot.count() > 0).sorted(java.util.Comparator.comparingInt(HotbarSlot::index)).toList();
		if (complete && occupied.isEmpty()) return "Nothing in hotbar.";
		if (slots.isEmpty()) return "Hotbar unknown.";
		String contents = occupied.stream().map(slot -> stack(slot.itemId(), slot.count()) + " in " + ORDINALS.get(slot.index()) + " slot"
			+ (slot.index() == selected ? " (selected)" : "")).collect(Collectors.joining("; "));
		String result = "Hotbar: " + (complete && occupied.size() == 1 ? "only " : "")
			+ (occupied.isEmpty() ? "observed slots empty" : contents) + ".";
		if (!complete) result += " Unobserved slots unknown.";
		else if (occupied.size() > 1 && occupied.size() < 9) result += " Other slots empty.";
		if (selected < 0) result += " Selected slot unknown.";
		else if (slots.stream().anyMatch(slot -> slot.index() == selected && slot.count() == 0))
			result += " Selected " + ORDINALS.get(selected) + " slot is empty.";
		else if (slots.stream().noneMatch(slot -> slot.index() == selected)) result += " Selected slot " + selected + " unobserved.";
		return result;
	}

	public static String equipment(String mainHand, Map<String, String> equipment) {
		boolean noArmor = ARMOR.stream().allMatch(slot -> empty(equipment.get(slot)));
		if (noArmor && empty(mainHand) && empty(equipment.get("offhand"))) return "Wearing and holding nothing.";
		var parts = new ArrayList<String>();
		if (noArmor) parts.add("No armor");
		else {
			String worn = ARMOR.stream().filter(slot -> equipment.containsKey(slot) && !empty(equipment.get(slot)))
				.map(slot -> slot + " " + item(equipment.get(slot))).collect(Collectors.joining(", "));
			if (!worn.isEmpty()) parts.add("Wearing " + worn);
			var unknown = ARMOR.stream().filter(slot -> !equipment.containsKey(slot)).toList();
			if (!unknown.isEmpty()) parts.add("Armor unknown: " + String.join(", ", unknown));
			else if (ARMOR.stream().anyMatch(slot -> empty(equipment.get(slot)))) parts.add("Other armor slots empty");
		}
		parts.add(hand("Main hand", mainHand));
		parts.add(hand("offhand", equipment.get("offhand")));
		return String.join("; ", parts) + ".";
	}

	public static String mainHand(String id) {
		return hand("Main hand", id) + ".";
	}

	public static String vitals(Map<String, ? extends Number> values) {
		var parts = new ArrayList<String>();
		Number health = values.get("health"), maxHealth = values.get("maxHealth");
		parts.add(health == null ? "Health unknown" : "Health " + number(health) + (maxHealth == null ? " (maximum unknown)" : "/" + number(maxHealth)));
		Number food = values.get("food");
		parts.add(food == null ? "food unknown" : food.doubleValue() == 20 ? "food full" : "food " + number(food) + "/20");
		if (values.containsKey("saturation")) parts.add("saturation " + number(values.get("saturation")));
		if (values.containsKey("air")) {
			Number air = values.get("air"), maxAir = values.get("maxAir");
			parts.add(maxAir != null && air.doubleValue() == maxAir.doubleValue() ? "air full"
				: "air " + number(air) + (maxAir == null ? " (maximum unknown)" : "/" + number(maxAir)));
		}
		var extras = new TreeMap<>(values);
		for (String key : List.of("health", "maxHealth", "food", "saturation", "air", "maxAir")) extras.remove(key);
		if (health == null && maxHealth != null) parts.add("maximum health " + number(maxHealth));
		if (!values.containsKey("air") && values.containsKey("maxAir")) parts.add("maximum air " + number(values.get("maxAir")));
		if (!extras.isEmpty()) parts.add("additional vitals " + extras);
		return String.join("; ", parts) + ".";
	}

	public static String durability(int slot, String id, int remaining, int maximum) {
		return item(id) + " " + remaining + "/" + maximum + " (inventory slot " + slot + ")";
	}

	private static String stack(String id, int count) {
		return (count == 1 ? "" : count + " ") + item(id);
	}

	private static boolean empty(String id) { return "minecraft:air".equals(id); }
	private static String hand(String label, String id) {
		return label + (id == null || id.isBlank() ? " unknown" : empty(id) ? " empty" : ": " + item(id));
	}
	private static String number(Number value) { return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString(); }
}
