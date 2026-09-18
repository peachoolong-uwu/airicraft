package ai.moeru.airicraft.agent.reflex;

/** Recovery owns movement until the land-combat controller's preconditions hold again. */
enum CombatRecovery {
    READY, REACH_DRY_GROUND;

    CombatRecovery next(boolean touchingWater, boolean grounded, boolean safeStanding) {
        if (touchingWater) return REACH_DRY_GROUND;
        if (this == REACH_DRY_GROUND && !(grounded && safeStanding)) return this;
        return READY;
    }
}
