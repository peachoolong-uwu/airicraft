package ai.moeru.airicraft.agent.spatial;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TravelBoundsTest {
	@Test void checksWholeMovementEnvelopeIncludingHeadroomAndJumpArc() {
		var bounds = new TravelBounds(0,60,0,10,66,10);
		assertTrue(bounds.permitsMovement(1,64,1,9,64,9,false));
		assertFalse(bounds.permitsMovement(1,66,1,9,66,9,false));
		assertTrue(bounds.contains(1,65,1)); assertTrue(bounds.contains(5,65,1));
		assertFalse(bounds.permitsMovement(1,65,1,5,65,1,true));
		assertFalse(bounds.permitsMovement(1,64,1,11,64,1,false));
		assertFalse(bounds.permitsMovement(1,64,1,1,59,1,false));
	}
	@Test void plannerStrategyCannotEnlargeUserIntersection() {
		var user = new TravelBounds(0,60,0,10,80,10);
		var larger = new TravelBounds(-10,0,-10,30,100,30);
		assertFalse(user.includes(larger));
		assertEquals(user,user.intersect(larger));
	}
}
