package ai.moeru.airicraft.wrapper;

import picocli.CommandLine.Command;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

@Command(name = "stream", mixinStandardHelpOptions = true,
	description = "Keep one wrapper connection for driver code. Read JSONL {id,op:status} or {id,op:call,name,arguments}; emit response: JSON lines. EOF closes the stream; it does not cancel admitted work.")
final class AgentToolsStreamCommand implements Callable<Integer> {
	private final MinecraftTransport transport;
	private final PrintWriter out;
	AgentToolsStreamCommand(MinecraftTransport transport, PrintWriter out) { this.transport = transport; this.out = out; }
	@Override public Integer call() {
		out.println("status: ok");
		out.println("command: agent tools stream");
		out.println("protocol: airicraft-tools-jsonl-v1");
		out.flush();
		try {
			DriverToolStream.serve(transport, new InputStreamReader(System.in, StandardCharsets.UTF_8), out);
			return 0;
		} catch (Exception error) {
			DriverToolStream.reply(out, "", false, null, error.getMessage());
			return 1;
		}
	}
}
