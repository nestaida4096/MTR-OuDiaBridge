# MTR4 door closure and departure investigation

Target: Minecraft 1.20.4 / MTR 4.0.5.

## Result

No MTR4 vehicle patch is applied at this stage. The MTR4 implementation already has an explicit door cooldown gate in the departure path. Copying the MTR3 dwell-time bypass fix into MTR4 would be incorrect because MTR4 does not use the MTR3 `Train` lifecycle or its dwell override.

## Relevant MTR4 behavior

The MTR4 `org.mtr.core.data.Vehicle` lifecycle has these safeguards:

1. During `Vehicle.simulate`, `doorCooldown` is set to `DOOR_MOVE_TIME + DOOR_DELAY` while a door multiplier or door override is active. Otherwise the cooldown counts down by the elapsed simulation time.
2. `Vehicle.startUp` requests `vehicleExtraData.closeDoors()`, but it only assigns the new departure index, resets dwell state, and starts vehicle movement when `doorCooldown == 0`.
3. In the stopped-at-platform path, rail progress is only advanced when the cooldown is zero. The start-up attempt is repeated on subsequent simulation ticks while the vehicle remains stopped.
4. The route-block check is separate from door closure. A blocked rail path still prevents the vehicle from advancing; the door cooldown does not override rail reservation or signal checks.

## Why no code change is made

The MTR3 defect was caused by a forced dwell-time pass after a fixed tick threshold even while the door value remained open. That specific forced-pass mechanism is not part of the MTR4 departure path inspected here. MTR4 uses a separate cooldown and checks it before movement begins.

No new patch should be introduced without a reproducible MTR4 4.0.5 case showing that the cooldown gate can be bypassed or that the vehicle moves before the doors are closed.

## In-game verification still required

- Verify a normal platform stop: doors open, close, then departure.
- Verify a delayed departure and an early departure.
- Verify a vehicle waiting behind a blocked rail/signal while doors are closing.
- Verify manual door override and the automatic-to-manual interaction.
- Verify both Fabric and Forge runtime behavior.

These are runtime checks; source inspection alone does not establish that all configurations behave correctly.
