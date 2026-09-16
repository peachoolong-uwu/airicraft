package ai.moeru.airicraft.os;

import com.google.gson.JsonElement;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.Predicate;

/** Embedded guest execution only. Scheduling and world authority stay in Java. */
public final class GraalSkillEngine implements AutoCloseable {
	public static final String POLICY = "graal-java-v1";
	private static final Predicate<Source> ALL_SOURCES = ignored -> true;
	private final Engine engine = Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
	private final Set<Instance> instances = ConcurrentHashMap.newKeySet();
	private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("airicraft-skill-deadline").factory());
	private final AtomicBoolean closed = new AtomicBoolean();
	private final Source kernel;

	public GraalSkillEngine() throws IOException {
		try (var input = GraalSkillEngine.class.getResourceAsStream("/airicraft/os/skill-kernel.js")) {
			if (input == null) throw new IOException("skill_kernel_missing");
			kernel = Source.newBuilder("js", new String(input.readAllBytes(), StandardCharsets.UTF_8), "airicraft-skill-kernel.js").buildLiteral();
		}
	}
	public synchronized Instance open(String source, String mode, JsonElement input) {
		if (closed.get()) throw new IllegalStateException("engine_closed");
		if (instances.size() >= 32) throw new IllegalStateException("skill_capacity");
		if (source == null || source.getBytes(StandardCharsets.UTF_8).length > 65_536) throw new IllegalArgumentException("skill_source_limit");
		if (!Set.of("generator", "offers").contains(mode)) throw new IllegalArgumentException("invalid_skill_mode");
		String encoded = OsJson.encode(input);
		var instance = new Instance(mode);
		instances.add(instance);
		instance.initialize(source, encoded);
		return instance;
	}
	@Override public synchronized void close() {
		if (!closed.compareAndSet(false, true)) return;
		for (var instance : Set.copyOf(instances)) instance.close();
		deadlines.shutdownNow(); engine.close(true);
	}

	public final class Instance implements AutoCloseable {
		private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("airicraft-skill").factory());
		private final AtomicBoolean stopped = new AtomicBoolean();
		private final AtomicBoolean evaluating = new AtomicBoolean();
		private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
		private final String mode;
		private volatile Context context;
		private Value guest;
		private CompletableFuture<Void> ready;
		private Instance(String mode) { this.mode = mode; }
		private void initialize(String source, String input) {
			ready = submit(() -> {
				context = Context.newBuilder("js").engine(engine).allowHostAccess(HostAccess.NONE)
					.allowHostClassLookup(name -> false).allowHostClassLoading(false).allowIO(IOAccess.NONE)
					.allowCreateThread(false).allowCreateProcess(false).allowNativeAccess(false)
					.allowEnvironmentAccess(EnvironmentAccess.NONE).allowPolyglotAccess(PolyglotAccess.NONE)
					.in(InputStream.nullInputStream()).out(new OutputLimit()).err(new OutputLimit())
					.resourceLimits(ResourceLimits.newBuilder().statementLimit(200_000, ALL_SOURCES).build()).build();
				if (stopped.get()) { context.close(true); throw new IllegalStateException("skill_stopped"); }
				guest = context.eval(kernel);
				var factory = context.eval(Source.newBuilder("js", "(function(os, input) {\n'use strict';\n" + source
					+ "\n; return {main: typeof main === 'function' ? main : null, offers: typeof offers === 'function' ? offers : null};\n})", "skill.js").buildLiteral());
				guest.invokeMember("initialize", factory, input, mode); return null;
			}, 10_000);
		}
		public CompletableFuture<JsonElement> resume(JsonElement input) { return evaluate("resume", input); }
		public CompletableFuture<JsonElement> offers(JsonElement input) { return evaluate("offers", input); }
		private CompletableFuture<JsonElement> evaluate(String method, JsonElement input) {
			if (!method.equals(mode.equals("offers") ? "offers" : "resume")) return CompletableFuture.failedFuture(new IllegalArgumentException("skill_mode_mismatch"));
			String json = OsJson.encode(input);
			if (stopped.get()) return CompletableFuture.failedFuture(new IllegalStateException("skill_stopped"));
			if (!evaluating.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IllegalStateException("skill_busy"));
			return ready.thenCompose(ignored -> submit(() -> {
				context.resetLimits(); return OsJson.parse(guest.invokeMember(method, json).asString());
			}, 1000)).whenComplete((result, error) -> evaluating.set(false));
		}
		private <T> CompletableFuture<T> submit(Supplier<T> body, long millis) {
			var result = new CompletableFuture<T>();
			if (stopped.get()) return CompletableFuture.failedFuture(new IllegalStateException("skill_stopped"));
			pending.add(result);
			try {
				executor.execute(() -> {
					if (stopped.get()) { result.completeExceptionally(new IllegalStateException("skill_stopped")); pending.remove(result); return; }
					var deadline = deadlines.schedule(() -> {
						result.completeExceptionally(new IllegalStateException("skill_deadline")); close();
					}, millis, TimeUnit.MILLISECONDS);
					try { result.complete(body.get()); }
					catch (Throwable failure) { result.completeExceptionally(failure); close(); }
					finally { deadline.cancel(false); pending.remove(result); }
				});
			} catch (RuntimeException failure) { pending.remove(result); result.completeExceptionally(failure); }
			return result;
		}
		@Override public void close() {
			if (!stopped.compareAndSet(false, true)) return;
			for (var future : pending) future.completeExceptionally(new IllegalStateException("skill_stopped"));
			pending.clear(); executor.shutdownNow();
			var active = context; if (active != null) active.close(true);
			instances.remove(this);
		}
	}
	private static final class OutputLimit extends OutputStream {
		private int bytes;
		@Override public void write(int value) throws IOException { if (++bytes > 4096) throw new IOException("skill_output_limit"); }
	}
}
