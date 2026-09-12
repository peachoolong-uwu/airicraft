package ai.moeru.airicraft.agent.spatial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SurfaceTerrainTest {
	@Test void permitsSwimmingAndFloatingDropsInTheTopWaterLayer() {
		// Shore log pickup: water occupies Y61..62, swimming feet and floating drops are at Y62.
		assertTrue(SurfaceTerrain.isSurfacePosition(62, 62, true, true));
		assertTrue(SurfaceTerrain.isSurfacePosition(63, 62, true, true));
		assertFalse(SurfaceTerrain.isSurfacePosition(61, 62, true, true));
	}

	@Test void solidGroundStillRequiresFeetAboveTheSurface() {
		assertTrue(SurfaceTerrain.isSurfacePosition(63, 62, true, false));
		assertFalse(SurfaceTerrain.isSurfacePosition(62, 62, true, false));
		assertFalse(SurfaceTerrain.isSurfacePosition(61, 62, true, false));
	}

	@Test void sourceBlocksMayOccupyTheSurfaceButNotBelowIt() {
		assertTrue(SurfaceTerrain.isSurfacePosition(62, 62, false, false));
		assertFalse(SurfaceTerrain.isSurfacePosition(61, 62, false, false));
		assertFalse(SurfaceTerrain.isSurfacePosition(61, 62, false, true));
	}
}
