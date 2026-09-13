package ai.moeru.airicraft.agent.events;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Observes physical episodes; never owns movement or chooses recovery. */
public final class PhysicalEventObserver {
	public record Position(double x, double y, double z) {
		public Map<String, Object> payload() { return Map.of("x", x, "y", y, "z", z); }
	}
	public record Sample(long tick, String dimension, Position position, Position velocity,
		boolean grounded, boolean water, boolean submerged, boolean climbing, boolean flying,
		boolean directionalInput, boolean burning, boolean lowAir, int air, float health,
		Map<String, Object> context) {
		public Sample { context = Map.copyOf(context); }
	}
	public record Event(Map<String, Object> payload) {}
	private static final double SIGNIFICANT_FALL = 1.5;
	private static final double SIGNIFICANT_DISPLACEMENT = 2.0;
	private static final long UPDATE_TICKS = 40;
	private Sample previous;
	private Episode fall;
	private Episode displacement;
	private final Map<String, Episode> hazards = new LinkedHashMap<>();

	public List<Event> observe(Sample sample) {
		if (previous != null && sample.tick() <= previous.tick()) return List.of();
		if (previous != null && !sample.dimension().equals(previous.dimension())) reset();
		List<Event> events = new ArrayList<>();
		observeHazard("burning", sample.burning(), sample, events);
		observeHazard("low_air", sample.lowAir(), sample, events);
		if (previous != null) {
			boolean unsupported = !sample.grounded() && !sample.water() && !sample.climbing() && !sample.flying();
			if (fall == null && unsupported && sample.position().y() < previous.position().y()) fall = new Episode(previous);
			boolean fallingThisTick = fall != null;
			if (fall != null) {
				double drop = fall.start.position().y() - sample.position().y();
				if (!fall.reported && drop >= SIGNIFICANT_FALL) emit(events, "fall", "started", fall, sample, "");
				else if (fall.reported && unsupported && sample.tick() - fall.lastReportTick >= UPDATE_TICKS)
					emit(events, "fall", "updated", fall, sample, "");
				if (!unsupported) {
					if (fall.reported) emit(events, "fall", "ended", fall, sample,
						sample.grounded() ? "landed" : sample.water() ? "entered_water" : "supported_movement");
					fall = null;
				}
			}
			// This reports a fact (movement without directional input), not inferred intent or cause.
			boolean observeDisplacement = !sample.directionalInput() && !sample.flying() && !fallingThisTick;
			if (!observeDisplacement) {
				if (displacement != null && displacement.reported)
					emit(events, "displacement", "ended", displacement, sample, "movement_mode_changed");
				displacement = null;
			} else {
				if (displacement == null) displacement = new Episode(previous);
				displacement.quietTicks = distance(previous.position(), sample.position()) < .001 ? displacement.quietTicks + 1 : 0;
				double distance = distance(displacement.start.position(), sample.position());
				if (!displacement.reported && distance >= SIGNIFICANT_DISPLACEMENT)
					emit(events, "displacement", "started", displacement, sample, "");
				else if (displacement.reported && sample.tick() - displacement.lastReportTick >= UPDATE_TICKS
					&& distance(displacement.lastReportPosition, sample.position()) >= SIGNIFICANT_DISPLACEMENT)
					emit(events, "displacement", "updated", displacement, sample, "");
				if (displacement.quietTicks >= 10) {
					if (displacement.reported) emit(events, "displacement", "ended", displacement, sample, "motion_stopped");
					displacement = null;
				}
			}
		}
		previous = sample;
		return List.copyOf(events);
	}

	private void observeHazard(String kind, boolean active, Sample sample, List<Event> events) {
		Episode episode = hazards.get(kind);
		if (active && episode == null) {
			episode = new Episode(sample);
			hazards.put(kind, episode);
			emit(events, kind, "started", episode, sample, "");
		} else if (active && sample.tick() - episode.lastReportTick >= UPDATE_TICKS
			&& (sample.health() <= episode.lastHealth - 2 || kind.equals("low_air") && sample.air() <= episode.lastAir - 20)) {
			emit(events, kind, "updated", episode, sample, "");
		} else if (!active && episode != null) {
			emit(events, kind, "ended", episode, sample, "condition_cleared");
			hazards.remove(kind);
		}
	}

	private static void emit(List<Event> events, String kind, String phase, Episode episode, Sample now, String endReason) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("kind", kind);
		payload.put("phase", phase);
		payload.put("episodeId", kind + ":" + episode.start.tick());
		payload.put("startTick", episode.start.tick());
		payload.put("observationTick", now.tick());
		payload.put("durationTicks", now.tick() - episode.start.tick());
		payload.put("dimension", now.dimension());
		payload.put("from", episode.start.position().payload());
		payload.put("position", now.position().payload());
		payload.put("velocity", now.velocity().payload());
		payload.put("distanceBlocks", distance(episode.start.position(), now.position()));
		payload.put("dropBlocks", Math.max(0, episode.start.position().y() - now.position().y()));
		payload.put("grounded", now.grounded());
		payload.put("touchingWater", now.water());
		payload.put("submerged", now.submerged());
		payload.put("climbing", now.climbing());
		payload.put("directionalInput", now.directionalInput());
		payload.put("burning", now.burning());
		payload.put("air", now.air());
		payload.put("health", now.health());
		payload.put("healthChange", now.health() - episode.start.health());
		payload.put("startContext", episode.start.context());
		payload.put("currentContext", now.context());
		if (!endReason.isEmpty()) payload.put("endReason", endReason);
		events.add(new Event(Map.copyOf(payload)));
		episode.reported = true;
		episode.lastReportTick = now.tick();
		episode.lastReportPosition = now.position();
		episode.lastHealth = now.health();
		episode.lastAir = now.air();
	}

	private static double distance(Position a, Position b) {
		return Math.sqrt(Math.pow(a.x()-b.x(),2) + Math.pow(a.y()-b.y(),2) + Math.pow(a.z()-b.z(),2));
	}

	public void reset() {
		previous = null;
		fall = null;
		displacement = null;
		hazards.clear();
	}

	private static final class Episode {
		final Sample start;
		boolean reported;
		long lastReportTick;
		Position lastReportPosition;
		int quietTicks;
		float lastHealth;
		int lastAir;
		Episode(Sample start) { this.start = start; }
	}
}
