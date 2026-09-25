package ai.moeru.airicraft.playtest;

/** Server-thread ownership of the automatic empty-host freeze. */
public final class HostedEmptyPause {
	public enum Action { NONE, FREEZE, RESUME }
	private boolean ownsFreeze;
	public Action update(boolean empty, boolean frozen) {
		if (empty && !frozen) { ownsFreeze = true; return Action.FREEZE; }
		if (!empty && ownsFreeze) { ownsFreeze = false; return Action.RESUME; }
		return Action.NONE;
	}
}
