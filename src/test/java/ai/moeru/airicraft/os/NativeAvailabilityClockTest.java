package ai.moeru.airicraft.os;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeAvailabilityClockTest {
	@Test void onlyCompletedCoveredTicksExtendTheCurrentMaterialWindow() {
		var clock = new NativeAvailabilityClock();
		assertFalse(clock.snapshot().available());
		clock.begin("stock-a");
		assertEquals(0, clock.snapshot().throughTick());
		clock.complete(true, "stock-a");
		assertTrue(clock.snapshot().available());
		assertEquals(0, clock.snapshot().fromTick());
		assertEquals(1, clock.snapshot().throughTick());
		clock.begin("stock-a"); clock.complete(true, "stock-a");
		assertEquals(0, clock.snapshot().fromTick());
		assertEquals(2, clock.snapshot().throughTick());
		clock.begin("stock-a"); clock.complete(true, "stock-b");
		assertFalse(clock.snapshot().available());
		assertEquals(3, clock.snapshot().throughTick());
		clock.begin("stock-b"); clock.complete(true, "stock-b");
		assertEquals("stock-b", clock.snapshot().stamp());
		assertEquals(3, clock.snapshot().fromTick());
		assertEquals(4, clock.snapshot().throughTick());
	}

	@Test void anInterveningAuthorityChangeCannotBeHiddenByRestoringTheSameFacts() {
		var clock = new NativeAvailabilityClock();
		clock.begin("stock-a"); clock.complete(true, "stock-a");
		var beforePause = clock.snapshot();
		clock.begin("stock-a"); clock.complete(false, "stock-a");
		assertEquals(beforePause, clock.snapshot());
		clock.begin("stock-a");
		clock.invalidate();
		clock.complete(true, "stock-a");
		assertFalse(clock.snapshot().available());
		assertEquals(2, clock.snapshot().throughTick());
		clock.begin("stock-a"); clock.complete(true, "stock-a");
		assertEquals(2, clock.snapshot().fromTick());
		assertEquals(3, clock.snapshot().throughTick());
		clock.begin(null); clock.complete(true, null);
		assertFalse(clock.snapshot().available());
	}

	@Test void anUnfinishedTickBreaksClockIdentityAndCannotSupplyMissingHistory() {
		var clock = new NativeAvailabilityClock();
		clock.begin("stock-a"); clock.complete(true, "stock-a");
		String oldClock = clock.snapshot().clockId();
		clock.begin("stock-a");
		clock.begin("stock-a");
		assertNotEquals(oldClock, clock.snapshot().clockId());
		assertFalse(clock.snapshot().available());
		clock.complete(true, "stock-a");
		assertEquals(0, clock.snapshot().fromTick());
		assertEquals(1, clock.snapshot().throughTick());
	}
}
