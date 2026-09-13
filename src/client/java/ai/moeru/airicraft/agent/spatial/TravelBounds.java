package ai.moeru.airicraft.agent.spatial;

/** Inclusive permitted cells, including body clearance and terrain edits. */
public record TravelBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
	public TravelBounds {
		if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("empty_travel_bounds");
	}
	public boolean contains(int x, int y, int z) { return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ; }
	public boolean includes(TravelBounds other) { return contains(other.minX, other.minY, other.minZ) && contains(other.maxX, other.maxY, other.maxZ); }
	public TravelBounds intersect(TravelBounds other) {
		return new TravelBounds(Math.max(minX,other.minX), Math.max(minY,other.minY), Math.max(minZ,other.minZ),
			Math.min(maxX,other.maxX), Math.min(maxY,other.maxY), Math.min(maxZ,other.maxZ));
	}
	public boolean permitsMovement(int x, int y, int z, int tx, int ty, int tz, boolean jumping) {
		// A conservative swept box covers intermediate diagonal, jump and falling cells.
		// It intentionally does not infer a curved route from two valid destinations.
		return contains(Math.min(x,tx), Math.min(y,ty), Math.min(z,tz))
			&& contains(Math.max(x,tx), Math.max(y,ty) + (jumping ? 2 : 1), Math.max(z,tz));
	}
}
