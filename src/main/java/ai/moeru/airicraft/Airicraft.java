package ai.moeru.airicraft;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Airicraft implements ModInitializer {
	public static final String MOD_ID = "airicraft";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
			ai.moeru.airicraft.memory.InteractionLogbookRecorder::flushTick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(
			ai.moeru.airicraft.os.NativeProgressRuntime::stop);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(
			ai.moeru.airicraft.os.NativeAvailabilityRuntime::stop);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			ai.moeru.airicraft.memory.InteractionLogbookRecorder.flushTick(server);
			ai.moeru.airicraft.memory.InteractionLogbook.flush();
		});
	}
}
