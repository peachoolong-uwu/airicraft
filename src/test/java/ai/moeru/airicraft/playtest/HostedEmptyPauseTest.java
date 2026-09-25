package ai.moeru.airicraft.playtest;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static ai.moeru.airicraft.playtest.HostedEmptyPause.Action.*;

class HostedEmptyPauseTest {
	@Test void freezesEmptyHostAndResumesOnJoinWithoutRepeatedCommands() {
		var pause = new HostedEmptyPause();
		assertEquals(FREEZE, pause.update(true, false));
		assertEquals(NONE, pause.update(true, true));
		assertEquals(RESUME, pause.update(false, true));
		assertEquals(NONE, pause.update(false, false));
		assertEquals(FREEZE, pause.update(true, false));
	}
	@Test void doesNotUnfreezeAnExistingManualPause() {
		var pause = new HostedEmptyPause();
		assertEquals(NONE, pause.update(true, true));
		assertEquals(NONE, pause.update(false, true));
	}
}
