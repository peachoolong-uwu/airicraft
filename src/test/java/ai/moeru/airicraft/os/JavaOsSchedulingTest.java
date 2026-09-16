package ai.moeru.airicraft.os;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class JavaOsSchedulingTest {
	@Test void fishingYieldsWithinAConfirmedTickBudgetAndPreservesAReadyBite() {
		var policy = new WorkScheduler();
		policy.update(List.of(new WorkScheduler.Offer("plant", Set.of("farmer"), "land", 1, "farm", true)));
		policy.advance(0, 10, Set.of(), false);
		assertEquals("wait_for_bite", policy.choose(false, null, new WorkScheduler.Activity("cast", "fishing", "waiting", false)).reason());
		policy.advance(10, 109, Set.of(), false);
		assertEquals("retrieve_bite", policy.choose(false, null, new WorkScheduler.Activity("cast", "fishing", "waiting", true)).reason());
		policy.advance(109, 110, Set.of(), false);
		assertEquals("reel_in", policy.choose(false, null, new WorkScheduler.Activity("cast", "fishing", "waiting", false)).reason());
	}
	@Test void aMissingProgressSampleCannotEraseTheClockHighWaterMark() {
		var observations = new ObservationIndex("epoch", () -> 0);
		observations.publish("farm", "one", 1, true, new com.google.gson.JsonArray(), "clock", 20L, 0);
		observations.publish("farm", "two", 2, false, new com.google.gson.JsonArray(), "unavailable:farm", null, 0);
		assertThrows(IllegalArgumentException.class, () -> observations.publish("farm", "three", 3, true, new com.google.gson.JsonArray(), "clock", 19L, 0));
	}
	@Test void readyLandWorkBeatsFishingAndOldWorkEventuallyBeatsPriority() {
		var policy = new WorkScheduler();
		var fishing = new WorkScheduler.Offer("fish", Set.of("fisher"), "fishing", 100, null, true);
		var wheat = new WorkScheduler.Offer("wheat", Set.of("farmer"), "land", 1, "farm", true);
		var sheep = new WorkScheduler.Offer("sheep", Set.of("shepherd"), "land", 10, "pen", true);
		policy.update(List.of(fishing, wheat, sheep));
		assertEquals("sheep", policy.choose(true, null).offerId());
		policy.advance(0, 2400, Set.of("farmer"), true);
		assertEquals("wheat", policy.choose(true, null).offerId());
		policy.served(wheat);
		assertEquals("sheep", policy.choose(true, null).offerId());
	}
	@Test void boundedContextVisitsOweOutsideWorkATurn() {
		var policy = new WorkScheduler();
		var pen = new WorkScheduler.Offer("shear", Set.of("shepherd"), "land", 10, "pen", true);
		var farm = new WorkScheduler.Offer("plant", Set.of("farmer"), "land", 1, "farm", true);
		policy.update(List.of(pen, farm));
		assertEquals("close_context", policy.choose(true, new WorkScheduler.Context("pen", "ready", 8, 20)).kind());
		assertEquals("plant", policy.choose(true, null).offerId());
	}
	@Test void unknownFactsAndUncoveredTimeCannotSatisfyGrowthWaits() {
		var now = new AtomicLong(); var observations = new ObservationIndex("epoch", now::get);
		var condition = OsJson.parse("{\"scope\":\"farm\",\"path\":[\"ripe\"],\"equals\":true}");
		var options = OsJson.parse("{\"deadline\":{\"clock\":\"eligible_ticks\",\"scope\":\"farm\",\"ticks\":10}}").getAsJsonObject();
		var wait = observations.waitFor(condition, options, Set.of("farm"));
		observations.publish("farm", "capture1", 1, true, OsJson.parse("[{\"path\":[\"ripe\"],\"known\":false}]").getAsJsonArray(), "clock", 3L, 0);
		assertEquals("pending", observations.poll(wait).get("status").getAsString());
		now.set(3000);
		assertEquals("pending", observations.poll(wait).get("status").getAsString());
		observations.publish("farm", "capture2", 2, true, OsJson.parse("[{\"path\":[\"ripe\"],\"known\":true,\"value\":false}]").getAsJsonArray(), "clock", 100L, 0);
		assertEquals("pending", observations.poll(wait).get("status").getAsString());
		observations.publish("farm", "capture3", 3, true, OsJson.parse("[{\"path\":[\"ripe\"],\"known\":true,\"value\":true}]").getAsJsonArray(), "clock", 101L, 0);
		assertEquals("met", observations.poll(wait).get("status").getAsString());
	}
}
