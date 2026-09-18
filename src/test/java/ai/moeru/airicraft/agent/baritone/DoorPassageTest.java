package ai.moeru.airicraft.agent.baritone;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoorPassageTest {
	@Test
	void sidewaysOpenDoorBlocksNorthwardExitFromInsideItsOwnCell() {
		Box player = new Box(-50.801, 66, -102.8125, -50.201, 67.8, -102.2125);
		Box openPanel = new Box(-51, 66, -103, -50, 68, -102.8125);
		Box closedPanel = new Box(-51, 66, -103, -50.8125, 68, -102);
		Vec3d north = new Vec3d(0, 0, -0.8);
		assertTrue(DoorPassage.blocks(player, north, openPanel));
		assertFalse(DoorPassage.blocks(player, north, closedPanel));
		assertFalse(DoorPassage.cleared(player, north, openPanel));
		assertTrue(DoorPassage.cleared(player.offset(0, 0, -1), north, openPanel));
	}

	@Test
	void ordinaryClosedDoorBlocksEntryAndRestoresOnlyAfterWholeBodyPasses() {
		Box panel = new Box(0, 0, 0, 0.1875, 2, 1);
		Vec3d east = new Vec3d(0.8, 0, 0);
		Box approaching = new Box(-0.6, 0, 0.2, 0, 1.8, 0.8);
		assertTrue(DoorPassage.blocks(approaching, east, panel));
		assertFalse(DoorPassage.cleared(approaching.offset(0.7, 0, 0), east, panel));
		assertTrue(DoorPassage.cleared(approaching.offset(0.9, 0, 0), east, panel));
		assertFalse(DoorPassage.blocks(approaching.offset(0.9, 0, 0), east, panel));
	}

	@Test
	void entrySweepIncludesFarEdgeDoorBeforeVanillaCanOpenItUntracked() {
		Box body = new Box(-0.8, 0, 0.2, -0.2, 1.8, 0.8);
		Box farPanel = new Box(0.8125, 0, 0, 1, 2, 1);
		assertTrue(DoorPassage.blocks(body, new Vec3d(1.8, 0, 0), farPanel));
	}

	@Test
	void panelsBesideTheRouteAndBeyondTheNextStepAreUntouched() {
		Box body = new Box(0.2, 0, 0.2, 0.8, 1.8, 0.8);
		Vec3d north = new Vec3d(0, 0, -0.8);
		assertFalse(DoorPassage.blocks(body, north, new Box(0, 0, 0, 0.1875, 2, 1)));
		assertFalse(DoorPassage.blocks(body, north, new Box(0, 0, -2, 1, 2, -1.8125)));
	}
}
