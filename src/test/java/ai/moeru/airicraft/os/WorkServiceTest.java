package ai.moeru.airicraft.os;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class WorkServiceTest {
	@TempDir Path directory;
	@Test void aPreviouslyReadyContextFailureStopsSchedulingAfterFreshObservation() throws Exception {
		LongSupplier millis = () -> System.nanoTime() / 1_000_000;
		try (var minecraft = new OsMinecraftFixture(); var effects = new NativeEffects(minecraft::call, new EffectJournal(directory), "save", millis)) {
			effects.start();
			var first = effects.observe().getAsJsonObject("frame");
			var config = new OsConfiguration(OsJson.obj("bindOpenContainer", true), first);
			var retained = effects.retain("container:home", frame -> OsJson.obj("windowId", "window-a", "syncId", 7));
			long deadline = System.nanoTime() + 5_000_000_000L;
			while (!effects.context().phase().equals("ready") && System.nanoTime() < deadline) { Thread.sleep(10); effects.pollContext(false); }
			assertEquals("ready", effects.context().phase());
			var service = new WorkService(new InvocationTree(), new ResourceLedger(), effects, config, new NativeViews(config, first, millis), millis);
			service.refresh();
			minecraft.client.submit(() -> { minecraft.chest.open = false; minecraft.driver.tick(); }).get();
			boolean failed = false;
			while (System.nanoTime() < deadline) {
				var receipt = minecraft.call("os_inspect", OsJson.obj("id", retained.get("id"))).get().getAsJsonObject("receipt");
				if (NativeEffects.released(receipt)) { failed = OsJson.text(receipt, "state").equals("FAILED"); break; }
				Thread.sleep(10);
			}
			assertTrue(failed, "the native context must finish its failed cleanup before testing the stale cached-ready receipt");
			service.refresh();
			assertEquals("native_context_failed", assertThrows(IllegalStateException.class, () -> service.tick(false)).getMessage());
		}
	}
}
