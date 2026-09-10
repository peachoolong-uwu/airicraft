package ai.moeru.airicraft.dashboard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardFrameCaptureTest {
	@Test
	void unchangedPixelsAreSkippedButOneChangedPixelAndNewSessionsAreRetained() {
		var filter = new DashboardFrameCapture.UnchangedFrameFilter();
		int[] pixels = {0xff000000, 0xff123456};
		assertTrue(filter.changed("one", pixels));
		assertFalse(filter.changed("one", pixels.clone()));
		pixels[1]++;
		assertTrue(filter.changed("one", pixels));
		assertFalse(filter.changed("one", pixels));
		assertTrue(filter.changed("two", pixels));
	}
}
