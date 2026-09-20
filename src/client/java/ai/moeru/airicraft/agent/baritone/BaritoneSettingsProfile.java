package ai.moeru.airicraft.agent.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;

import java.util.Objects;

public final class BaritoneSettingsProfile {
	private BaritoneSettingsProfile() {
	}

	public static void apply() {
		apply(BaritoneAPI.getSettings());
	}

	public static void apply(Settings settings) {
		Objects.requireNonNull(settings, "settings");
		apply(new SettingsTarget() {
			@Override
			public void avoidance(boolean value) { settings.avoidance.value = value; }

			@Override
			public void mobAvoidanceRadius(int value) { settings.mobAvoidanceRadius.value = value; }

			@Override
			public void mobAvoidanceCoefficient(double value) { settings.mobAvoidanceCoefficient.value = value; }

			@Override
			public void chatControl(boolean value) {
				settings.chatControl.value = value;
			}

			@Override
			public void chatControlAnyway(boolean value) {
				settings.chatControlAnyway.value = value;
			}

			@Override
			public void prefixControl(boolean value) {
				settings.prefixControl.value = value;
			}

			@Override
			public void allowInventory(boolean value) {
				settings.allowInventory.value = value;
			}

			@Override
			public void allowPlace(boolean value) {
				settings.allowPlace.value = value;
			}

			@Override
			public void allowParkour(boolean value) {
				settings.allowParkour.value = value;
			}

			@Override
			public void allowDownward(boolean value) {
				settings.allowDownward.value = value;
			}

			@Override
			public void allowWaterBucketFall(boolean value) {
				settings.allowWaterBucketFall.value = value;
			}

			@Override
			public void allowBreak(boolean value) {
				settings.allowBreak.value = value;
			}

			@Override
			public void autoTool(boolean value) {
				settings.autoTool.value = value;
			}

			@Override
			public void randomLooking(double value) {
				settings.randomLooking.value = value;
			}

			@Override
			public void randomLooking113(double value) {
				settings.randomLooking113.value = value;
			}

			@Override
			public void freeLook(boolean value) {
				settings.freeLook.value = value;
			}

			@Override
			public void exploreForBlocks(boolean value) {
				settings.exploreForBlocks.value = value;
			}
		});
	}

	static void apply(SettingsTarget settings) {
		Objects.requireNonNull(settings, "settings");

		settings.avoidance(true);
		settings.mobAvoidanceRadius(16);
		settings.mobAvoidanceCoefficient(4.0D);
		settings.chatControl(false);
		settings.chatControlAnyway(false);
		settings.prefixControl(false);
		settings.allowInventory(true);
		settings.allowPlace(true);
		settings.allowParkour(true);
		settings.allowDownward(false);
		settings.allowWaterBucketFall(false);

		settings.allowBreak(true);
		settings.autoTool(true);
		settings.randomLooking(0.0D);
		settings.randomLooking113(0.0D);
		settings.freeLook(false);
		settings.exploreForBlocks(false);
	}

	interface SettingsTarget {
		void avoidance(boolean value);

		void mobAvoidanceRadius(int value);

		void mobAvoidanceCoefficient(double value);

		void chatControl(boolean value);

		void chatControlAnyway(boolean value);

		void prefixControl(boolean value);

		void allowInventory(boolean value);

		void allowPlace(boolean value);

		void allowParkour(boolean value);

		void allowDownward(boolean value);

		void allowWaterBucketFall(boolean value);

		void allowBreak(boolean value);

		void autoTool(boolean value);

		void randomLooking(double value);

		void randomLooking113(double value);

		void freeLook(boolean value);

		void exploreForBlocks(boolean value);
	}
}
