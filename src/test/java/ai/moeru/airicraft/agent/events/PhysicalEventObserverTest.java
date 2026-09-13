package ai.moeru.airicraft.agent.events;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PhysicalEventObserverTest {
	private static PhysicalEventObserver.Sample sample(long tick, double x, double y, boolean ground, boolean input, boolean water) {
		return new PhysicalEventObserver.Sample(tick, "minecraft:overworld", new PhysicalEventObserver.Position(x,y,0),
			new PhysicalEventObserver.Position(0,ground ? 0 : -.4,0), ground, water, false, false, false,
			input, false, false, 300,20, Map.of("taskId","nav-1","target",Map.of("x",318,"y",-10,"z",280)));
	}
	@Test void fallThroughTargetReportsOriginLandingAndTaskWithoutCallingItKnockback() {
		var observer = new PhysicalEventObserver();
		assertTrue(observer.observe(sample(1,318,-9,true,true,false)).isEmpty());
		assertTrue(observer.observe(sample(2,318,-9.08,false,true,false)).isEmpty());
		assertTrue(observer.observe(sample(3,318,-10,false,false,false)).isEmpty());
		var start = observer.observe(sample(4,318,-10.6,false,false,false));
		assertEquals("started",start.getFirst().payload().get("phase"));
		var end = observer.observe(sample(8,318,-12,true,false,false)).getFirst().payload();
		assertEquals("fall",end.get("kind"));
		assertEquals("ended",end.get("phase"));
		assertEquals("landed",end.get("endReason"));
		assertEquals(Map.of("x",318.0,"y",-9.0,"z",0.0),end.get("from"));
		assertEquals(3.0,end.get("dropBlocks"));
		assertEquals("nav-1",((Map<?,?>)end.get("startContext")).get("taskId"));
		assertFalse(end.containsKey("cause"));
	}
	@Test void ordinaryStepAndJumpStayQuiet() {
		var observer = new PhysicalEventObserver();
		for (var s: List.of(sample(1,0,0,true,true,false),sample(2,0,1.25,false,true,false),
			sample(3,0,1.1,false,true,false),sample(4,0,0,true,true,false),sample(5,0,-.5,false,true,false),sample(6,0,-1,true,true,false)))
			assertTrue(observer.observe(s).isEmpty());
	}
	@Test void waterDisplacementIsSummarizedAndDirectionalMovementIsNotReportedAsDrift() {
		var observer = new PhysicalEventObserver();
		List<PhysicalEventObserver.Event> events = new ArrayList<>();
		for(int t=1;t<=70;t++) events.addAll(observer.observe(sample(t,t*.1,0,true,false,true)));
		assertEquals(2,events.size());
		assertEquals("displacement",events.getFirst().payload().get("kind"));
		assertEquals(true,events.getFirst().payload().get("touchingWater"));
		assertEquals(false,events.getFirst().payload().get("directionalInput"));
		for(int t=71;t<=82;t++) events.addAll(observer.observe(sample(t,7,0,true,false,true)));
		assertEquals("ended",events.getLast().payload().get("phase"));
		assertEquals("motion_stopped",events.getLast().payload().get("endReason"));
		observer.reset();
		for(int t=1;t<=60;t++) assertTrue(observer.observe(sample(t,t*.3,0,true,true,true)).isEmpty());
	}
	@Test void hazardsStartImmediatelyCoalesceAndEnd() {
		var observer = new PhysicalEventObserver();
		List<PhysicalEventObserver.Event> events = new ArrayList<>();
		for(int t=1;t<=120;t++) {
			var s=sample(t,0,0,true,false,true);
			events.addAll(observer.observe(new PhysicalEventObserver.Sample(t,s.dimension(),s.position(),s.velocity(),true,true,true,false,false,false,true,true,40,20,s.context())));
		}
		assertEquals(2,events.size()); // Unchanged hazards do not repeat every tick.
		var ended=observer.observe(sample(121,0,0,true,false,false));
		assertEquals(2,ended.size());
		assertTrue(ended.stream().allMatch(e->e.payload().get("phase").equals("ended")));
	}
	@Test void hazardEscalationIsReportedWithoutPerTickDamageSpam() {
		var observer=new PhysicalEventObserver();
		List<PhysicalEventObserver.Event> events=new ArrayList<>();
		for(int t=1;t<=45;t++) {
			var s=sample(t,0,0,true,false,true);
			events.addAll(observer.observe(new PhysicalEventObserver.Sample(t,s.dimension(),s.position(),s.velocity(),true,true,true,false,false,false,true,true,
				t<20 ? 40 : 10,t<20 ? 20 : 18,s.context())));
		}
		assertEquals(4,events.size());
		assertEquals("updated",events.getLast().payload().get("phase"));
	}

	@Test void slowDriftAccumulatesAndFallKeepsOriginalTaskAfterCompletion() {
		var observer = new PhysicalEventObserver();
		List<PhysicalEventObserver.Event> events = new ArrayList<>();
		for(int t=1;t<=250;t++) events.addAll(observer.observe(sample(t,t*.01,0,true,false,true)));
		assertEquals(1,events.size());
		observer.reset();
		observer.observe(sample(1,318,-9,true,true,false));
		observer.observe(sample(2,318,-10,false,false,false));
		var landed=sample(4,318,-12,true,false,false);
		var result=observer.observe(new PhysicalEventObserver.Sample(4,landed.dimension(),landed.position(),landed.velocity(),
			true,false,false,false,false,false,false,false,300,20,Map.of("taskId","nav-2")));
		assertEquals(2,result.size()); // Fall onset and end; no duplicate displacement episode on landing.
		assertEquals("nav-1",((Map<?,?>)result.getLast().payload().get("startContext")).get("taskId"));
		assertEquals("nav-2",((Map<?,?>)result.getLast().payload().get("currentContext")).get("taskId"));
	}

	@Test void resetDimensionChangeAndDuplicateTicksDoNotInventMovement() {
		var observer=new PhysicalEventObserver();
		observer.observe(sample(1,0,0,true,false,false));
		assertTrue(observer.observe(sample(1,100,0,true,false,false)).isEmpty());
		var s=sample(2,100,0,true,false,false);
		assertTrue(observer.observe(new PhysicalEventObserver.Sample(2,"minecraft:the_nether",s.position(),s.velocity(),true,false,false,false,false,false,false,false,300,20,Map.of())).isEmpty());
		observer.reset();
		assertTrue(observer.observe(sample(3,1000,0,true,false,false)).isEmpty());
	}
}
