package ai.moeru.airicraft.agent.events;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlowMiningObserverTest {
	@Test void warnsOnlyAfterSustainedSlowBreakingAndRateLimitsAcrossBlocks() {
		var observer = new SlowMiningObserver();
		assertFalse(observer.observe(0, "stone1", 150));
		assertFalse(observer.observe(39, "stone1", 150));
		assertTrue(observer.observe(40, "stone1", 150));
		assertFalse(observer.observe(100, "stone1", 150));
		assertFalse(observer.observe(101, "stone2", 150));
		assertFalse(observer.observe(141, "stone2", 150));
		assertTrue(observer.observe(640, "stone2", 150));
	}
	@Test void stalledFastBreakWarnsButFinishedAndInterruptedBreaksDoNot() {
		var observer = new SlowMiningObserver();
		assertFalse(observer.observe(0, "stone", 10));
		assertFalse(observer.observe(99, "stone", 10));
		assertTrue(observer.observe(100, "stone", 10));
		observer.reset();
		assertFalse(observer.observe(101, "stone", 150));
		assertFalse(observer.observe(120, null, 0));
		assertFalse(observer.observe(140, "stone", 150));
		assertFalse(observer.observe(160, "stone", 150));
		assertTrue(observer.observe(180, "stone", 150));
	}
}
