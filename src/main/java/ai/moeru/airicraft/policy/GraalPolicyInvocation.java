package ai.moeru.airicraft.policy;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Generator execution only. Guest code exchanges JSON effects; it never receives a Java object. */
public final class GraalPolicyInvocation implements AutoCloseable {
	public static final int MAX_SOURCE_CHARS = 32_768;
	public static final int MAX_VALUE_CHARS = 16_384;
	private static final String KERNEL = """
		(() => {
		  let iterator;
		  const policy = Object.freeze({
		    observeContainer: () => ({operation: 'observe_container', arguments: {}}),
		    withdraw: (syncId, items) => ({operation: 'withdraw', arguments: {syncId, items}}),
		    closeContainer: syncId => ({operation: 'close_container', arguments: {syncId}})
		  });
		  return {
		    initialize(factory, input) {
		      const main = factory();
		      if (typeof main !== 'function') throw Error('Define function* main(policy, input)');
		      iterator = main(policy, JSON.parse(input));
		      if (!iterator || typeof iterator.next !== 'function') throw Error('main must be a generator');
		    },
		    resume(input) {
		      const step = iterator.next(JSON.parse(input));
		      return JSON.stringify({done: step.done, value: step.value === undefined ? null : step.value});
		    }
		  };
		})()
		""";
	private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("airicraft-policy").factory());
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicBoolean resuming = new AtomicBoolean();
	private volatile Context context;
	private Value guest;
	private final CompletableFuture<Void> ready;

	public GraalPolicyInvocation(String source, JsonElement input) {
		if (source == null || source.isBlank() || source.length() > MAX_SOURCE_CHARS) throw new IllegalArgumentException("policy_source_limit");
		String encoded = encode(input);
		ready = submit(() -> {
			context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false")
				.allowHostAccess(HostAccess.NONE).allowHostClassLookup(name -> false).allowHostClassLoading(false)
				.allowIO(IOAccess.NONE).allowCreateThread(false).allowCreateProcess(false).allowNativeAccess(false)
				.allowEnvironmentAccess(EnvironmentAccess.NONE).allowPolyglotAccess(PolyglotAccess.NONE)
				.in(java.io.InputStream.nullInputStream()).out(java.io.OutputStream.nullOutputStream()).err(java.io.OutputStream.nullOutputStream())
				.resourceLimits(ResourceLimits.newBuilder().statementLimit(200_000, ignored -> true).build()).build();
			if (closed.get()) { context.close(true); throw new IllegalStateException("policy_cancelled"); }
			guest = context.eval("js", KERNEL);
			Value factory = context.eval("js", "(function() { 'use strict';\n" + source + "\n; return main; })");
			guest.invokeMember("initialize", factory, encoded);
			return null;
		}, 10);
	}

	public CompletableFuture<JsonElement> resume(JsonElement result) {
		String encoded = encode(result);
		if (!resuming.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IllegalStateException("policy_busy"));
		return ready.thenCompose(ignored -> submit(() -> {
			context.resetLimits();
			String json = guest.invokeMember("resume", encoded).asString();
			if (json.length() > MAX_VALUE_CHARS) throw new IllegalArgumentException("policy_value_limit");
			return JsonParser.parseString(json);
		}, 1)).whenComplete((value, error) -> resuming.set(false));
	}

	private <T> CompletableFuture<T> submit(Supplier<T> action, int timeoutSeconds) {
		if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("policy_cancelled"));
		return CompletableFuture.supplyAsync(() -> {
			if (closed.get()) throw new IllegalStateException("policy_cancelled");
			return action.get();
		}, worker).orTimeout(timeoutSeconds, TimeUnit.SECONDS).whenComplete((value, error) -> {
			if (error != null) close();
		});
	}

	private static String encode(JsonElement value) {
		String json = (value == null ? JsonNull.INSTANCE : value).toString();
		if (json.length() > MAX_VALUE_CHARS) throw new IllegalArgumentException("policy_value_limit");
		return json;
	}

	@Override public void close() {
		if (!closed.compareAndSet(false, true)) return;
		worker.shutdownNow();
		Context active = context;
		// Cancelling a guest must never wait on the Minecraft tick thread.
		if (active != null) CompletableFuture.runAsync(() -> active.close(true));
	}
}
