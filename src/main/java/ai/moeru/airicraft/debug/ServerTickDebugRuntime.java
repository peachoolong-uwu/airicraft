package ai.moeru.airicraft.debug;

/** Process-local owner for the currently running logical server's tick-debug state. */
public final class ServerTickDebugRuntime {
	private static final ServerTickDebugController CONTROLLER = new ServerTickDebugController();
	private static volatile TickAnchor tickAnchor = new TickAnchor(0, 0);
	public record TickAnchor(long debugServerTick, long serverTick) {}
	public static TickAnchor tickAnchor() { return tickAnchor; }

	private ServerTickDebugRuntime() {
	}

	public static ServerTickDebugController controller() {
		return CONTROLLER;
	}

	public static boolean beginServerTick() {
		return CONTROLLER.beginServerTick();
	}

	public static void completeServerTick(long serverTick) {
		CONTROLLER.completeServerTick();
		// Publish both coordinates together from the server thread, never two racing client reads.
		tickAnchor = new TickAnchor(CONTROLLER.status().serverTickId(), serverTick);
	}

	public static void reset() {
		CONTROLLER.reset();
	}
}
