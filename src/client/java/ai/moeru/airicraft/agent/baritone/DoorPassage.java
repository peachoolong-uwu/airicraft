package ai.moeru.airicraft.agent.baritone;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** A door needs interaction only when its panel intersects the upcoming body sweep. */
public final class DoorPassage {
	private DoorPassage() {}

	public static boolean blocks(Box body, Vec3d travel, Box panel) {
		return body.stretch(travel).intersects(panel);
	}

	public static boolean cleared(Box body, Vec3d travel, Box panel) {
		if (Math.abs(travel.x) > Math.abs(travel.z))
			return travel.x > 0 ? body.minX > panel.maxX : body.maxX < panel.minX;
		return travel.z > 0 ? body.minZ > panel.maxZ : body.maxZ < panel.minZ;
	}
}
