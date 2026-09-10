package ai.moeru.airicraft.agent.baritone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritoneSettingsProfileTest {
	@Test
	void appliesConservativeSettingsProfile() {
		FakeSettingsTarget target = new FakeSettingsTarget();

		BaritoneSettingsProfile.apply(target);

		assertTrue(target.avoidance);
		assertEquals(16, target.mobAvoidanceRadius);
		assertEquals(4.0D, target.mobAvoidanceCoefficient);
		assertFalse(target.chatControl);
		assertFalse(target.chatControlAnyway);
		assertFalse(target.prefixControl);
		assertFalse(target.allowInventory);
		assertTrue(target.allowPlace);
		assertTrue(target.allowParkour);
		assertFalse(target.allowDownward);
		assertFalse(target.allowWaterBucketFall);
		assertTrue(target.allowBreak);
		assertTrue(target.autoTool);
		assertEquals(0.0D, target.randomLooking);
		assertEquals(0.0D, target.randomLooking113);
		assertFalse(target.freeLook);
		assertFalse(target.exploreForBlocks);
	}

	private static final class FakeSettingsTarget implements BaritoneSettingsProfile.SettingsTarget {
		private boolean avoidance;
		private int mobAvoidanceRadius;
		private double mobAvoidanceCoefficient;

		@Override
		public void avoidance(boolean value) { avoidance = value; }

		@Override
		public void mobAvoidanceRadius(int value) { mobAvoidanceRadius = value; }

		@Override
		public void mobAvoidanceCoefficient(double value) { mobAvoidanceCoefficient = value; }

		private boolean chatControl = true;
		private boolean chatControlAnyway = true;
		private boolean prefixControl = true;
		private boolean allowInventory = true;
		private boolean allowPlace = true;
		private boolean allowParkour = true;
		private boolean allowDownward = true;
		private boolean allowWaterBucketFall = true;
		private boolean allowBreak = false;
		private boolean autoTool = false;
		private double randomLooking = 1.0D;
		private double randomLooking113 = 1.0D;
		private boolean freeLook = true;
		private boolean exploreForBlocks = true;

		@Override
		public void chatControl(boolean value) {
			chatControl = value;
		}

		@Override
		public void chatControlAnyway(boolean value) {
			chatControlAnyway = value;
		}

		@Override
		public void prefixControl(boolean value) {
			prefixControl = value;
		}

		@Override
		public void allowInventory(boolean value) {
			allowInventory = value;
		}

		@Override
		public void allowPlace(boolean value) {
			allowPlace = value;
		}

		@Override
		public void allowParkour(boolean value) {
			allowParkour = value;
		}

		@Override
		public void allowDownward(boolean value) {
			allowDownward = value;
		}

		@Override
		public void allowWaterBucketFall(boolean value) {
			allowWaterBucketFall = value;
		}

		@Override
		public void allowBreak(boolean value) {
			allowBreak = value;
		}

		@Override
		public void autoTool(boolean value) {
			autoTool = value;
		}

		@Override
		public void randomLooking(double value) {
			randomLooking = value;
		}

		@Override
		public void randomLooking113(double value) {
			randomLooking113 = value;
		}

		@Override
		public void freeLook(boolean value) {
			freeLook = value;
		}

		@Override
		public void exploreForBlocks(boolean value) {
			exploreForBlocks = value;
		}
	}
}
