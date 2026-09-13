package ai.moeru.airicraft.agent.llm;

/** Bounded display-only tail. Never enters conversation history or tool execution. */
public final class PlannerStreamPreview {
	private static final int LIMIT = 16_384;
	private final StringBuilder text = new StringBuilder();
	private boolean truncated;

	public synchronized void append(String delta) {
		text.append(delta);
		if (text.length() > LIMIT) {
			text.delete(0, text.length() - LIMIT);
			truncated = true;
		}
	}

	public synchronized String text() {
		return (truncated ? "[earlier preview omitted]\n" : "") + text;
	}
}
