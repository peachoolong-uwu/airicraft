package ai.moeru.airicraft.agent.reflex;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.agent.reflex.CombatPositioning.*;

class CombatPositioningTest {
	private static final Cell ORIGIN = new Cell(0, 64, 0);

	@Test void retreatFacesThePackAndUsesBackpedalingRegardlessOfWorldOrientation() {
		assertEquals(new Steering(false, true, false, false), steering(0, 0, 0, -1, 0, 5));
		assertEquals(new Steering(false, true, false, false), steering(0, 0, -1, 0, 5, 0));
		assertEquals(new Steering(false, true, false, false), steering(0, 0, 1, 0, -5, 0));
		assertEquals(new Steering(false, true, false, false), steering(0, 0, 0, 1, 0, -5));
	}

	@Test void circlingStrafesRelativeToTheThreatHeadingInsteadOfTurningTheCameraAway() {
		assertEquals(new Steering(false, false, true, false), steering(0, 0, 1, 0, 0, 5));
		assertEquals(new Steering(false, false, false, true), steering(0, 0, -1, 0, 0, 5));
		assertEquals(new Steering(false, true, false, true), steering(0, 0, -1, -1, 0, 5));
		assertEquals(new Steering(false, false, false, false), steering(0, 0, 0, 0, 0, 5));
	}

	@Test void escapesAcrossAPincerInsteadOfChargingEitherSide() {
		var decision = choose(ORIGIN, grid(6, Set.of()), List.of(mob(-3, 0, .15), mob(3, 0, .15)), null);
		assertNotNull(decision.nextStep());
		assertEquals(0, decision.nextStep().x());
		assertNotEquals(0, decision.nextStep().z());
		assertTrue(decision.risk() < decision.standingRisk());
	}

	@Test void pressureAccountsForTheWholePackNotJustTheNearestMob() {
		var graph = grid(6, Set.of());
		var nearest = mob(2, 0, .1);
		var one = choose(ORIGIN, graph, List.of(nearest), null);
		var swarm = choose(ORIGIN, graph, List.of(nearest, mob(-4, -1, .2), mob(-4, 0, .2), mob(-4, 1, .2)), null);
		assertTrue(one.route().getLast().x() <= 0, "Do not charge through the close attacker");
		assertNotEquals(one.nextStep(), swarm.nextStep());
		assertNotEquals(new Cell(-1, 64, 0), swarm.nextStep(), "Do not retreat straight into the farther pack");
	}

	@Test void fasterFlankerChangesTheEscapeDirection() {
		var graph = grid(6, Set.of());
		// Two flankers approach an open north/south escape passage from the west.
		graph.replaceAll((cell, edges) -> edges.stream().filter(edge -> edge.destination().x() == 0).toList());
		var fastNorth = choose(ORIGIN, graph, List.of(mob(-4, -4, .3), mob(-4, 4, .02)), null);
		var fastSouth = choose(ORIGIN, graph, List.of(mob(-4, -4, .02), mob(-4, 4, .3)), null);
		assertTrue(fastNorth.route().getLast().z() > fastSouth.route().getLast().z(), () -> fastNorth + " versus " + fastSouth);
	}

	@Test void allRouteStepsRespectWallsAndDisconnectedSafeGroundIsNeverSelected() {
		var blocked = Set.of(new Cell(-1, 64, -1), new Cell(-1, 64, 0), new Cell(-1, 64, 1));
		var graph = grid(5, blocked);
		Cell disconnected = new Cell(-100, 64, 0);
		graph.put(disconnected, List.of());
		var result = choose(ORIGIN, graph, List.of(mob(2, -1, .12), mob(2, 1, .12)), null);
		assertNotNull(result.nextStep());
		assertFalse(result.route().contains(disconnected));
		for (int i = 1; i < result.route().size(); i++) {
			Cell from = result.route().get(i - 1), to = result.route().get(i);
			assertTrue(graph.get(from).stream().anyMatch(edge -> edge.destination().equals(to)));
			assertFalse(blocked.contains(to));
		}
	}

	@Test void safeEndpointDoesNotJustifyRunningThroughAnAttacker() {
		var graph = new LinkedHashMap<Cell, List<Edge>>();
		Cell throughMob = new Cell(1, 64, 0), escape = new Cell(0, 64, -1);
		graph.put(ORIGIN, List.of(new Edge(throughMob, 4), new Edge(escape, 4)));
		for (int i = 1; i <= 5; i++) {
			graph.put(new Cell(i, 64, 0), List.of(new Edge(new Cell(i + 1, 64, 0), 4)));
			graph.put(new Cell(0, 64, -i), List.of(new Edge(new Cell(0, 64, -i - 1), 4)));
		}
		var result = choose(ORIGIN, graph, List.of(mob(1, 0, 0), mob(-3, 0, 0)), null);
		assertEquals(escape, result.nextStep(), result.toString());
	}

	@Test void boundedAndDeterministicAndCanHoldWhenNoTraversableExitExists() {
		var threats = List.of(mob(-3, 0, .1), mob(3, 0, .1));
		assertNull(choose(ORIGIN, Map.of(ORIGIN, List.of()), threats, null).nextStep());
		var graph = grid(6, Set.of());
		var first = choose(ORIGIN, graph, threats, null);
		assertEquals(first, choose(ORIGIN, graph, threats, null));
		assertTrue(first.expandedRoutes() <= BEAM_WIDTH * MAX_STEPS * 4);
	}

	@Test void opposingMobsAreRiskierThanTheSameCountOnOneSide() {
		assertTrue(risk(.5, .5, 0, List.of(mob(-4, 0, 0), mob(4, 0, 0)))
			> risk(.5, .5, 0, List.of(mob(4, -1, 0), mob(4, 1, 0))));
	}

	@Test void aSpacedPackEncouragesLateralMotionWithConsistentOrbitDirection() {
		var threats = List.of(mob(5, -1, .02), mob(5, 1, .02));
		var result = choose(ORIGIN, grid(6, Set.of()), threats, null);
		assertNotNull(result.nextStep());
		assertNotEquals(0, result.route().getLast().z());
		assertTrue(result.risk() < result.standingRisk());
	}

	@Test void readyFighterClosesToStrikeInsteadOfBackingAwayFromSpacedPack() {
		var threats = List.of(mob(4, -1, .1), mob(4, 1, .1));
		var result = choose(ORIGIN, grid(8, Set.of()), threats, null, ORIGIN, true);
		Cell end = result.route().getLast();
		double nearest = threats.stream().mapToDouble(t -> Math.hypot(t.x() - end.x() - .5, t.z() - end.z() - .5)).min().orElseThrow();
		assertTrue(nearest <= 3, result.toString());
		assertTrue(end.x() >= 0, "Do not back away when ready to strike");
	}

	@Test void rechargingFighterCirclesRatherThanRetreatingStraightBack() {
		var result = choose(ORIGIN, grid(8, Set.of()), List.of(mob(3, -1, .1), mob(3, 1, .1)), null, ORIGIN, false);
		assertNotNull(result.nextStep());
		assertNotEquals(0, result.nextStep().z(), result.toString());
	}

	@Test void repeatedReplansStayNearTheOriginalFightPosition() {
		Cell position = ORIGIN;
		for (int i = 0; i < 40; i++) {
			var threats = List.of(mob(position.x() + 2, position.z() - 1, .1), mob(position.x() + 2, position.z() + 1, .1));
			var result = choose(position, grid(12, Set.of()), threats, null, ORIGIN, i % 4 == 0);
			for (Cell step : result.route()) assertTrue(Math.hypot(step.x(), step.z()) <= 6, result.toString());
			if (result.nextStep() != null) position = result.nextStep();
		}
	}

	@Test void knockbackOutsideTheFightAreaAllowsOnlyStepsBackTowardIt() {
		Cell displaced = new Cell(8, 64, 0);
		var result = choose(displaced, grid(12, Set.of()), List.of(mob(10, 0, .1)), null, ORIGIN, true);
		assertNotNull(result.nextStep());
		assertTrue(result.nextStep().x() < 8, result.toString());
	}

	private static Threat mob(double x, double z, double speed) { return new Threat(x + .5, 64, z + .5, speed, 2.4); }
	private static Map<Cell, List<Edge>> grid(int radius, Set<Cell> blocked) {
		var graph = new LinkedHashMap<Cell, List<Edge>>();
		for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
			Cell from = new Cell(x, 64, z);
			if (blocked.contains(from)) continue;
			var edges = new ArrayList<Edge>();
			for (int[] direction : new int[][]{{-1, 0}, {0, -1}, {0, 1}, {1, 0}}) {
				Cell to = new Cell(x + direction[0], 64, z + direction[1]);
				if (Math.abs(to.x()) <= radius && Math.abs(to.z()) <= radius && !blocked.contains(to)) edges.add(new Edge(to, 4));
			}
			graph.put(from, List.copyOf(edges));
		}
		return graph;
	}
}
