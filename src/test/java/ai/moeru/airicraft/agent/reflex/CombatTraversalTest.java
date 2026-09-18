package ai.moeru.airicraft.agent.reflex;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import baritone.api.pathing.movement.MovementStatus;
class CombatTraversalTest {
    @Test void initializesBaritoneLifecycleBeforeFirstUpdateOrEvidenceRead() {
        var state = CombatTraversal.initialState();
        assertEquals(MovementStatus.PREPPING, state.getStatus());
        assertFalse(state.getStatus().isComplete());
        assertNotNull(state.getTarget());
        assertTrue(state.getInputStates().isEmpty());
    }
}
