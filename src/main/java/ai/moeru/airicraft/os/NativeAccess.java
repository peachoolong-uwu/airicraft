package ai.moeru.airicraft.os;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/** Internal Java bridge. The mod marshals calls to its client thread; guests never see it. */
@FunctionalInterface
public interface NativeAccess {
	CompletableFuture<JsonObject> call(String method, JsonObject arguments);
}
