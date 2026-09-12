# Cave exploration

`survey_cave` provides a compact observation for choosing the next short move. It casts 495 sparse first-hit rays, with a default distance of 12 blocks (allowed: 4–24). Results include up to eight visible standing candidates spread by direction, sixteen exposed ore blocks, twelve hazards, and local light levels. Returned positions count as fresh observations for exact block actions.

This is an alternative to repeated screenshots and hidden-block queries. It observes geometry around the player, including behind the current camera direction; it does not enforce darkness-limited eyesight. Sparse rays can miss small surfaces. Empty results do not prove absence, and `routeValidated: false` means a visible standing candidate may still be unreachable.

The planner prepares a shield, food, picks, torches and inventory space, remembers an entrance/return point, and disables path excavation and placement while traversing passages. It chooses short waypoints, surveys again, records useful junctions and tried branches, and restores its previous path settings afterward. Exact exposed ore coordinates can be approached and broken, then newly exposed faces surveyed. Home stock is read from dated logbook observations and confirmed by opening storage.

Live acceptance so far covers surface observations, visible water, an existing underground workstation passage, and a successful return with digging/placement disabled. It does not yet establish natural-cave discovery, ore collection using only this interface, or reliable exploration of a large cave network.
