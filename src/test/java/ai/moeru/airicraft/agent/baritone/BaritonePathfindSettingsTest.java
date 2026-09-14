package ai.moeru.airicraft.agent.baritone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritonePathfindSettingsTest {
	@Test
	void exposesAllNonJavaOnlySettingsWithoutBootstrappingMinecraft() {
		var settings = BaritonePathfindSettings.describeSettings("allow");
		@SuppressWarnings("unchecked")
		var properties = (java.util.Map<String, Object>) BaritonePathfindSettings.describeSettings("allowDownward").get("settings");

		assertTrue(properties.containsKey("allowDownward"));
		assertTrue(((java.util.Map<?, ?>) BaritonePathfindSettings.describeSettings("allowParkour").get("settings")).containsKey("allowParkour"));
		assertTrue(((java.util.Map<?, ?>) settings.get("settings")).size() <= 16);
		assertTrue(new com.google.gson.Gson().toJson(BaritonePathfindSettings.plannerSettingsSchema()).length() < 1000);
		assertTrue(BaritonePathfindSettings.plannerSettingsSchema().containsKey("additionalProperties"));
	}

	@Test
	void normalizesCamelCaseNamesForBaritoneParserLookup() {
		assertEquals("walkonwateronepenalty",
			BaritonePathfindSettings.parserSettingName("walkOnWaterOnePenalty"));
	}
}
