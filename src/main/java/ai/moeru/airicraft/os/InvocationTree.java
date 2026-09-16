package ai.moeru.airicraft.os;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structured ownership of skill instances and the single physical activity. Actor confined. */
public final class InvocationTree {
	public record Handle(String id, String parentId, long sequence) {}
	public record Execution(String id, String root, String parent, String definition, String phase, Set<String> grants) {}
	private final Map<String, Node> nodes = new LinkedHashMap<>();
	private long sequence;
	private String activity;
	private final Set<String> subscribers = new LinkedHashSet<>(), cleanupOwners = new LinkedHashSet<>();
	public String install(String definition, Set<String> grants, JsonContract output, boolean collectAll) {
		if (nodes.values().stream().filter(node -> node.parent == null).count() >= 12) throw new IllegalStateException("root_capacity");
		return create(null, definition, grants, output, collectAll).id;
	}
	public Handle spawn(String parentId, String definition, Set<String> grants, JsonContract output, boolean collectAll) {
		var parent = get(parentId); running(parent);
		if (parent.children.size() >= 64 || nodes.values().stream().mapToInt(node -> node.children.size()).sum() >= 256) throw new IllegalStateException("child_result_capacity");
		if (!parent.grants.containsAll(grants)) throw new IllegalStateException("capability_escalation");
		var child = create(parent, definition, grants, output, collectAll);
		long childSequence = ++parent.childSequence; parent.children.put(childSequence, child.id);
		return new Handle(child.id, parent.id, childSequence);
	}
	public JsonObject join(String parentId, Handle handle) {
		var parent = get(parentId);
		if (!parentId.equals(handle.parentId()) || !handle.id().equals(parent.children.get(handle.sequence()))) throw new IllegalArgumentException("invalid_child_handle");
		var child = get(handle.id());
		if (child.outcome == null) return OsJson.obj("status", "pending");
		var outcome = child.outcome.deepCopy(); parent.children.remove(handle.sequence()); nodes.remove(child.id); settle(parent); return outcome;
	}
	public void returned(String id, JsonElement result) {
		var node = get(id); if (!node.phase.equals("running")) return;
		try { node.value = node.output.check(result); }
		catch (IllegalArgumentException failure) { fail(id, "invalid_result"); return; }
		node.phase = "closing"; settle(node);
	}
	public void cancel(String id, String reason) { stop(get(id), "cancelled", reason); }
	public void fail(String id, String reason) { stop(get(id), "failure", reason); }
	public void attach(String id, Set<String> owners) {
		if (activity != null) throw new IllegalStateException("player_owned");
		if (owners.isEmpty()) throw new IllegalArgumentException("activity_requires_subscriber");
		owners.forEach(owner -> running(get(owner)));
		activity = id; subscribers.addAll(owners);
	}
	public void subscribe(String id, String owner) {
		if (!id.equals(activity) || stopActivity()) throw new IllegalStateException("activity_stopping");
		running(get(owner)); subscribers.add(owner);
	}
	public boolean stopActivity() { return activity != null && subscribers.isEmpty(); }
	public String activity() { return activity; }
	public Set<String> activityOwners() { var all = new HashSet<>(subscribers); all.addAll(cleanupOwners); return Set.copyOf(all); }
	public void release(String id, boolean released, boolean accounted) {
		if (!id.equals(activity)) throw new IllegalStateException("activity_unknown");
		if (!released || !accounted) return;
		var owners = activityOwners(); activity = null; subscribers.clear(); cleanupOwners.clear();
		for (String owner : owners) if (nodes.containsKey(owner)) settle(get(owner));
	}
	public String authorize(String id, String grant) {
		var node = get(id); running(node);
		if (!node.grants.contains(grant)) throw new IllegalStateException("operation_not_granted");
		return execution(id).root();
	}
	public Execution execution(String id) {
		var node = get(id); var root = node; while (root.parent != null) root = get(root.parent);
		return new Execution(id, root.id, node.parent, node.definition, node.phase, node.grants);
	}
	public JsonObject outcome(String id) { var result = get(id).outcome; return result == null ? null : result.deepCopy(); }
	public boolean running(String id) { return nodes.containsKey(id) && get(id).phase.equals("running"); }
	public boolean contains(String id) { return nodes.containsKey(id); }
	public int size() { return nodes.size(); }
	public List<String> ids() { return List.copyOf(nodes.keySet()); }
	public List<String> roots() { return nodes.values().stream().filter(node -> node.parent == null).map(node -> node.id).toList(); }
	public void retire(String root) {
		var node = get(root);
		if (node.parent != null || node.outcome == null) throw new IllegalStateException("invocation_unsettled");
		nodes.remove(root);
	}
	private Node create(Node parent, String definition, Set<String> grants, JsonContract output, boolean collectAll) {
		if (nodes.values().stream().filter(node -> node.outcome == null).count() >= 32) throw new IllegalStateException("invocation_capacity");
		int depth = parent == null ? 1 : parent.depth + 1;
		if (depth > 8) throw new IllegalStateException("invocation_depth");
		var node = new Node("invocation:" + ++sequence, parent == null ? null : parent.id, depth, definition, Set.copyOf(grants), output, collectAll);
		nodes.put(node.id, node); return node;
	}
	private void stop(Node node, String status, String reason) {
		if (node.outcome != null || node.cause != null) return;
		node.cause = OsJson.obj("status", status, "cause", OsJson.obj("reason", reason)); node.phase = "stopping";
		if (subscribers.remove(node.id) && subscribers.isEmpty()) cleanupOwners.add(node.id);
		if (status.equals("failure") && node.parent != null) {
			var parent = get(node.parent); if (!parent.collectAll) stop(parent, "failure", reason);
		}
		for (String child : List.copyOf(node.children.values())) if (nodes.containsKey(child)) stop(get(child), "cancelled", "parent_stopping");
		settle(node);
	}
	private void settle(Node node) {
		if (!Set.of("closing", "stopping").contains(node.phase) || subscribers.contains(node.id) || cleanupOwners.contains(node.id)) return;
		for (String child : node.children.values()) if (get(child).outcome == null) return;
		for (String child : node.children.values()) nodes.remove(child);
		node.children.clear(); node.phase = "terminal";
		node.outcome = node.cause == null ? OsJson.obj("status", "success", "value", node.value) : node.cause;
		if (node.parent != null && nodes.containsKey(node.parent)) settle(get(node.parent));
	}
	private static void running(Node node) { if (!node.phase.equals("running")) throw new IllegalStateException("invocation_closing"); }
	private Node get(String id) { var node = nodes.get(id); if (node == null) throw new IllegalArgumentException("invocation_unknown"); return node; }
	private static final class Node {
		final String id, parent, definition; final int depth; final Set<String> grants; final JsonContract output; final boolean collectAll;
		final Map<Long, String> children = new LinkedHashMap<>();
		long childSequence; String phase = "running"; JsonElement value; JsonObject cause, outcome;
		Node(String id, String parent, int depth, String definition, Set<String> grants, JsonContract output, boolean collectAll) {
			this.id = id; this.parent = parent; this.depth = depth; this.definition = definition; this.grants = grants; this.output = output; this.collectAll = collectAll;
		}
	}
}
