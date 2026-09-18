package ai.moeru.airicraft.agent.reflex;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatFocusTest {
	@Test void retainsNearestVisibleOpponentUntilItDiesOrBecomesUnavailable() {
		var focus = new CombatFocus();
		assertEquals("near", focus.select(List.of(new CombatFocus.Candidate("near", 2.6, true), new CombatFocus.Candidate("far", 4, true))));
		assertEquals("near", focus.select(List.of(new CombatFocus.Candidate("near", 2.8, true), new CombatFocus.Candidate("far", 2.5, true))));
		assertEquals("far", focus.select(List.of(new CombatFocus.Candidate("far", 2.5, true))));
		assertNull(focus.select(List.of()));
	}

	@Test void visibilityAndDisengagementAllowRetargetingAndNewFightResetsFocus() {
		var focus = new CombatFocus();
		assertEquals("visible", focus.select(List.of(new CombatFocus.Candidate("hidden", 1, false), new CombatFocus.Candidate("visible", 3, true))));
		assertEquals("near", focus.select(List.of(new CombatFocus.Candidate("visible", 7, true), new CombatFocus.Candidate("near", 2, true))));
		focus.clear();
		assertEquals("new", focus.select(List.of(new CombatFocus.Candidate("near", 3, true), new CombatFocus.Candidate("new", 2, true))));
	}
}
