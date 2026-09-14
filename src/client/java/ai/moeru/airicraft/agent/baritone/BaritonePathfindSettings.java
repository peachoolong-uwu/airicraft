package ai.moeru.airicraft.agent.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The JSON-safe part of Baritone's process-wide settings surface. Baritone marks
 * callback settings as Java-only; those cannot be expressed or safely injected
 * through a planner tool call.
 */
public final class BaritonePathfindSettings {
	private static final Set<String> JAVA_ONLY_SETTINGS = Set.of("allowJumpAt256", "logger", "notifier", "toaster");
	private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
		Map.entry("allowdownward", "Allow mining the block directly beneath the player. Disable this to prefer staircases over one-block shafts."),
		Map.entry("allowparkour", "Allow gap-jumping movement. This is faster but less reliable around corners and unsafe landings."),
		Map.entry("allowbreak", "Allow Baritone to break blocks while following a path."),
		Map.entry("allowplace", "Allow Baritone to place blocks for bridging, climbing, and other path movements."),
		Map.entry("allowwaterbucketfall", "Allow arbitrary falls when Baritone can attempt a water-bucket landing; reliability is limited."),
		Map.entry("allowdiagonaldescend", "Allow diagonal descents. This is riskier because adjacent blocks are not fully checked."),
		Map.entry("allowdiagonalascend", "Allow diagonal ascents, which are generally safer than diagonal descents."),
		Map.entry("allowparkourplace", "Allow placing blocks as part of parkour movement."),
		Map.entry("maxfallheightnowater", "Maximum safe fall height onto solid ground without a water bucket."),
		Map.entry("maxfallheightbucket", "Maximum fall height Baritone may attempt with a water bucket."),
		Map.entry("blockplacementpenalty", "Pathfinding cost penalty for placing a block; higher values conserve building blocks."),
		Map.entry("blockbreakadditionalpenalty", "Additional pathfinding cost for breaking a block when another route is available."),
		Map.entry("avoidance", "Enable mob and spawner avoidance calculations; this has a noticeable planning cost."),
		Map.entry("costheuristic", "A* heuristic. Larger values plan faster but may choose less optimal paths."),
		Map.entry("paththroughcachedonly", "Restrict paths to cached chunks only. Baritone advises against enabling this."),
		Map.entry("cutoffatloadboundary", "Cut paths at loaded-chunk boundaries instead of relying on simplified cached terrain."),
		Map.entry("allowinventory", "Allow Baritone to move inventory items into the hotbar."),
		Map.entry("autotool", "Automatically select the best available tool for breaking blocks."),
		Map.entry("allowonlyexposedores", "Only mine exposed ores, useful on servers with ore obfuscation.")
	);

	private BaritonePathfindSettings() {
	}

	public static Map<String, Object> plannerSettingsSchema() {
		return Map.of("type", "object", "minProperties", 1,
			"description", "Setting name to value. Use inspect_pathfind(query) to find names, types and descriptions; inspect_pathfind(names) reads current values. All non-Java settings remain supported.",
			"additionalProperties", Map.of("anyOf", List.of(Map.of("type", "boolean"), Map.of("type", "number"), Map.of("type", "string"))));
	}

	/** Full documentation is queried, never included in the fixed planner prefix. */
	public static Map<String, Object> describeSettings(String query) {
		var catalog = settingsCatalog();
		String term = query.toLowerCase(Locale.ROOT);
		var matches = catalog.entrySet().stream().filter(entry -> (entry.getKey() + " " + entry.getValue()).toLowerCase(Locale.ROOT).contains(term)).toList();
		var selected = new LinkedHashMap<String, Object>();
		matches.stream().limit(16).forEach(entry -> selected.put(entry.getKey(), entry.getValue()));
		return Map.of("settings", selected, "matched", matches.size(), "truncated", matches.size() > 16);
	}

	private static Map<String, Object> settingsCatalog() {
		LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
		try {
			Settings settings = BaritoneAPI.getSettings();
			for (Settings.Setting<?> setting : settings.allSettings) {
				if (setting.isJavaOnly()) {
					continue;
				}
				properties.put(setting.getName(), schemaFor(setting));
			}
		}
		catch (LinkageError | RuntimeException ignored) {
			for (Field field : Settings.class.getFields()) {
				if (field.getType() != Settings.Setting.class || JAVA_ONLY_SETTINGS.contains(field.getName())) {
					continue;
				}
				properties.put(field.getName(), fallbackSchemaFor(field));
			}
		}
		return properties;
	}

	private static Map<String, Object> fallbackSchemaFor(Field field) {
		String name = field.getName();
		LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", schemaType(settingValueType(field)));
		schema.put("description", DESCRIPTIONS.getOrDefault(name.toLowerCase(Locale.ROOT),
			"Configure Baritone's " + humanize(name) + " behavior. Values are validated by Baritone at runtime.")
			+ " Current and default values are reported when the live client is available.");
		return schema;
	}

	private static Class<?> settingValueType(Field field) {
		Type type = field.getGenericType();
		if (type instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length == 1) {
			Type argument = parameterized.getActualTypeArguments()[0];
			if (argument instanceof Class<?> valueClass) {
				return valueClass;
			}
		}
		return String.class;
	}

	private static String schemaType(Class<?> type) {
		if (type == Boolean.class) {
			return "boolean";
		}
		if (type == Integer.class || type == Long.class) {
			return "integer";
		}
		if (Number.class.isAssignableFrom(type)) {
			return "number";
		}
		return "string";
	}

	public static ApplyResult apply(JsonObject requestedSettings) {
		if (requestedSettings == null || requestedSettings.isEmpty()) {
			return ApplyResult.rejected("settings must be a non-empty object");
		}
		Settings settings = BaritoneAPI.getSettings();
		LinkedHashMap<Settings.Setting<?>, String> parsed = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : requestedSettings.entrySet()) {
			Settings.Setting<?> setting = settings.byLowerName.get(entry.getKey().toLowerCase(Locale.ROOT));
			if (setting == null) {
				return ApplyResult.rejected("unknown_setting " + entry.getKey());
			}
			if (setting.isJavaOnly()) {
				return ApplyResult.rejected("java_only_setting " + setting.getName());
			}
			String value = valueFor(setting, entry.getValue());
			if (value == null) {
				return ApplyResult.rejected("invalid_type " + setting.getName() + " expects " + settingType(setting));
			}
			parsed.put(setting, value);
		}

		LinkedHashMap<Settings.Setting<?>, Object> before = new LinkedHashMap<>();
		for (Settings.Setting<?> setting : parsed.keySet()) {
			before.put(setting, setting.value);
		}
		try {
			for (Map.Entry<Settings.Setting<?>, String> entry : parsed.entrySet()) {
				SettingsUtil.parseAndApply(settings, parserSettingName(entry.getKey().getName()), entry.getValue());
			}
		}
		catch (RuntimeException exception) {
			for (Map.Entry<Settings.Setting<?>, Object> entry : before.entrySet()) {
				restore(entry.getKey(), entry.getValue());
			}
			return ApplyResult.rejected("invalid_value " + exception.getMessage());
		}

		ArrayList<String> changed = new ArrayList<>();
		for (Settings.Setting<?> setting : parsed.keySet()) {
			changed.add(setting.getName() + "=" + SettingsUtil.settingValueToString(setting));
		}
		return ApplyResult.accepted(changed);
	}

	public static Map<String, Object> inspect(List<String> names) {
		Settings settings = BaritoneAPI.getSettings();
		Map<String, Object> result = new LinkedHashMap<>();
		for (String name : names) {
			Settings.Setting<?> setting = settings.byLowerName.get(name.toLowerCase(Locale.ROOT));
			if (setting == null || setting.isJavaOnly()) throw new IllegalArgumentException("unknown_or_java_only_setting " + name);
			result.put(setting.getName(), Map.of("value", SettingsUtil.settingValueToString(setting),
				"default", SettingsUtil.settingDefaultToString(setting)));
		}
		return result;
	}

	static String parserSettingName(String settingName) {
		return Objects.requireNonNull(settingName, "settingName").toLowerCase(Locale.ROOT);
	}

	private static Map<String, Object> schemaFor(Settings.Setting<?> setting) {
		LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", schemaType(setting));
		schema.put("description", descriptionFor(setting)
			+ " Default: " + SettingsUtil.settingDefaultToString(setting)
			+ ". Read live values with inspect_pathfind.");
		return schema;
	}

	private static String descriptionFor(Settings.Setting<?> setting) {
		String name = setting.getName();
		return DESCRIPTIONS.getOrDefault(name.toLowerCase(Locale.ROOT),
			"Configure Baritone's " + humanize(name) + " behavior. Use Baritone's serialized " + settingType(setting) + " format.");
	}

	private static String humanize(String name) {
		return name.replaceAll("(?<!^)([A-Z])", " $1").toLowerCase(Locale.ROOT);
	}

	private static String schemaType(Settings.Setting<?> setting) {
		return schemaType(setting.getValueClass());
	}

	private static String settingType(Settings.Setting<?> setting) {
		return SettingsUtil.settingTypeToString(setting);
	}

	private static String valueFor(Settings.Setting<?> setting, JsonElement value) {
		if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
			return null;
		}
		Class<?> type = setting.getValueClass();
		try {
			if (type == Boolean.class) {
				return value.getAsJsonPrimitive().isBoolean() ? Boolean.toString(value.getAsBoolean()) : null;
			}
			if (type == Integer.class || type == Long.class || Number.class.isAssignableFrom(type)) {
				return value.getAsJsonPrimitive().isNumber() ? value.getAsString() : null;
			}
			return value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
		}
		catch (RuntimeException ignored) {
			return null;
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void restore(Settings.Setting setting, Object value) {
		setting.value = value;
	}

	public record ApplyResult(boolean accepted, List<String> changed, String error) {
		public ApplyResult {
			changed = changed == null ? List.of() : List.copyOf(changed);
			error = error == null ? "" : error;
		}

		static ApplyResult accepted(List<String> changed) {
			return new ApplyResult(true, changed, "");
		}

		static ApplyResult rejected(String error) {
			return new ApplyResult(false, List.of(), Objects.requireNonNullElse(error, "invalid_settings"));
		}
	}
}
