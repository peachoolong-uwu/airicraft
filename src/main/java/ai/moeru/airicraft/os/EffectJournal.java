package ai.moeru.airicraft.os;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fsynced intent before admission, with bounded retained outcomes and no effect replay. */
public final class EffectJournal {
	private final Path path;
	private final Map<String, JsonObject> entries = new LinkedHashMap<>();
	public EffectJournal(Path directory) throws IOException {
		path = directory.resolve("effects.json");
		if (!Files.exists(path)) return;
		var stored = OsStorage.read(path, 8_388_608).getAsJsonObject();
		var records = stored.getAsJsonArray("records");
		if (!OsJson.digest(records).equals(OsJson.text(stored, "digest")) || records.size() > 1280) throw new IOException("journal_integrity");
		for (var item : records) {
			var entry = item.getAsJsonObject(); String key = key(entry.getAsJsonObject("intent").getAsJsonObject("id"));
			if (entries.put(key, entry.deepCopy()) != null) throw new IOException("journal_identity_conflict");
		}
	}
	public void record(JsonObject intent) throws IOException {
		intent = OsJson.object(OsJson.copy(intent)); String key = key(intent.getAsJsonObject("id"));
		var previous = entries.get(key);
		if (previous != null) { if (!previous.get("intent").equals(intent)) throw new IllegalStateException("journal_identity_conflict"); return; }
		if (unfinished().size() >= 1024) throw new IllegalStateException("journal_capacity");
		entries.put(key, OsJson.obj("intent", intent, "settled", false, "receipt", null));
		persist();
	}
	public void settle(JsonObject id, JsonObject receipt) throws IOException {
		var entry = entries.get(key(id)); if (entry == null) throw new IllegalArgumentException("journal_intent_unknown");
		boolean settled = OsJson.bool(receipt, "released", false) && OsJson.bool(receipt, "accountingComplete", false);
		if (OsJson.bool(entry, "settled", false)) {
			if (!settled || !entry.get("receipt").equals(receipt)) throw new IllegalStateException("journal_outcome_conflict"); return;
		}
		entry.add("receipt", OsJson.copy(receipt)); entry.addProperty("settled", settled); persist();
	}
	public List<JsonObject> unfinished() { return entries.values().stream().filter(entry -> !OsJson.bool(entry, "settled", false)).map(entry -> entry.getAsJsonObject("intent").deepCopy()).toList(); }
	public JsonArray recent() { var result = new JsonArray(); entries.values().stream().skip(Math.max(0, entries.size() - 32)).forEach(entry -> result.add(entry.deepCopy())); return result; }
	private void persist() throws IOException {
		long settled = entries.values().stream().filter(entry -> OsJson.bool(entry, "settled", false)).count();
		var iterator = entries.entrySet().iterator();
		while (settled > 256 && iterator.hasNext()) if (OsJson.bool(iterator.next().getValue(), "settled", false)) { iterator.remove(); settled--; }
		var records = new JsonArray(); entries.values().forEach(records::add);
		OsStorage.write(path, OsJson.obj("schemaVersion", 1, "records", records, "digest", OsJson.digest(records)), 8_388_608);
	}
	static String key(JsonObject id) {
		OsJson.keys(id, java.util.Set.of("epoch", "generation", "sequence"), java.util.Set.of("epoch", "generation", "sequence"));
		if (OsJson.number(id, "generation", 0) == 0 || OsJson.number(id, "sequence", 0) == 0) throw new IllegalArgumentException("invalid_native_id");
		return OsJson.text(id, "epoch") + "/" + id.get("generation").getAsLong() + "/" + id.get("sequence").getAsLong();
	}
}
