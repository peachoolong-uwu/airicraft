package ai.moeru.airicraft.agent.reflex;

import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import java.util.Map;

/** Reads Baritone's per-move executor, but never calls update() or applies its camera/input effects. */
final class CombatTraversal {
    private sealed interface State permits Idle, Active { }
    private record Idle(String outcome) implements State { }
    private record Active(Movement movement, MovementState state, CombatPositioning.Cell destination, long startedTick) implements State { }
    record Control(CombatPositioning.Steering steering, boolean jump, boolean sneak) { }
    private State state = new Idle("idle");

    static MovementState initialState() {
        return new MovementState().setStatus(baritone.api.pathing.movement.MovementStatus.PREPPING);
    }
    CombatPositioning.Cell destination() { return state instanceof Active a ? a.destination() : null; }
    void start(Movement movement, CombatPositioning.Cell destination, long tick) {
        state = new Active(movement, initialState(), destination, tick);
    }
    Map<String, Object> evidence() {
        if (state instanceof Active a) return Map.of("phase", a.state().getStatus().toString(), "destination", a.destination(), "startedTick", a.startedTick());
        return Map.of("phase", ((Idle)state).outcome());
    }
    Control tick(MinecraftClient client, CalculationContext context, Vec3d facing, long tick) {
        if (!(state instanceof Active a)) return stopped();
        // Fresh checks prevent the executor from switching tools or placing/breaking after terrain changes.
        a.movement().resetBlockCache();
        var breaking = a.movement().toBreak(context.bsi);
        var placing = a.movement().toPlace(context.bsi);
        if (tick - a.startedTick() > 60 || !breaking.isEmpty() || !placing.isEmpty()) {
            state = new Idle("failed: age=" + (tick-a.startedTick()) + " break=" + breaking + " place=" + placing);
            return stopped();
        }
        a.state().getInputStates().clear();
        a.movement().updateState(a.state());
        var inputs = a.state().getInputStates();
        if (a.state().getStatus().isComplete()) {
            // Some moves report block arrival before the player has landed.
            if (client.player.isOnGround() || a.state().getStatus() != baritone.api.pathing.movement.MovementStatus.SUCCESS) {
                state = new Idle(a.state().getStatus().toString());
                return stopped();
            }
        }
        if (Boolean.TRUE.equals(inputs.get(Input.CLICK_LEFT)) || Boolean.TRUE.equals(inputs.get(Input.CLICK_RIGHT))) {
            state = new Idle("rejected_block_interaction");
            return stopped();
        }
        double yaw = Math.toRadians(a.state().getTarget().getRotation().map(r -> r.getYaw()).orElse(client.player.getYaw()));
        int forward = on(inputs, Input.MOVE_FORWARD) - on(inputs, Input.MOVE_BACK);
        int left = on(inputs, Input.MOVE_LEFT) - on(inputs, Input.MOVE_RIGHT);
        double dx = -Math.sin(yaw) * forward + Math.cos(yaw) * left;
        double dz = Math.cos(yaw) * forward + Math.sin(yaw) * left;
        var p = client.player;
        var steering = CombatPositioning.steering(p.getX(), p.getZ(), p.getX() + dx, p.getZ() + dz, facing.x, facing.z);
        return new Control(steering, on(inputs, Input.JUMP) == 1, on(inputs, Input.SNEAK) == 1);
    }
    private static int on(Map<Input, Boolean> inputs, Input key) { return Boolean.TRUE.equals(inputs.get(key)) ? 1 : 0; }
    private static Control stopped() { return new Control(new CombatPositioning.Steering(false, false, false, false), false, false); }
}
