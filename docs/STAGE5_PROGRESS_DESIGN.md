# Stage 5 — OuDia progress comparison design

Target: MTR4 4.0.5 / Minecraft 1.20.4 / Java 17 / Architectury.

Status: design only. No runtime behavior or MTR4 state is changed by this document.

## 1. Existing command responsibilities

Keep the existing commands separate by purpose:

| Command | Responsibility | Binding behavior |
|---|---|---|
| `/oudiabridge time-match <vehicleId>` | Discover and rank trains that match the vehicle's current/next station pair and current clock | Read-only; does not bind |
| `/oudiabridge time-bind <vehicleId>` | Bind only when exactly one candidate is inside the scheduled station-pair interval | One-shot; writes the existing SavedData binding |
| `/oudiabridge timing <vehicleId> [currentTime]` | Analyze the already-bound train against the vehicle's current/next station pair | Read-only; uses the stored binding |

Do not merge these commands. Candidate discovery and progress analysis have different safety requirements: once a vehicle has a stored binding, progress analysis must not silently switch it to another train.

## 2. Findings from the current implementation

### `OudiaTimeMatcher`

- Uses the MTR4 simulation timestamp converted with `ZoneId.systemDefault()`.
- Requires the normalized route name and current/next station pair to match.
- Checks all consecutive station pairs in each train.
- Builds a scheduled interval from the current station's departure (arrival fallback) to the next station's arrival (departure fallback).
- Ranks candidates and exposes the candidates inside that interval.
- It is suitable for discovery and one-time binding, not for tracking the progress of an already-bound train.

### `OudiaScheduleAnalyzer` and `timing`

- Uses the stored timetable ID and train index.
- Looks only for the bound train's exact current/next station pair.
- Uses the same departure/arrival fallback rules and day-rollover timeline construction.
- Reports schedule times, current clock, remaining time to the next-station event, current speed, and distance to the next ATO stop when the next OuDia stop is a scheduled stop.
- Does not change MTR4 vehicle state.

### MTR4 snapshot data already available

The existing `VehicleSnapshot` contains the current/next station, `moving`, `simulationMillis`, speed, `railProgress`, distance to the next ATO stop, and the elapsed route/dwell estimates. These values are captured on the Simulator thread.

The snapshot does not currently provide a verified physical distance between the current and next timetable stations, nor a confirmed arrival/departure event timestamp for each station. `distanceToNextAtoStop` must not be treated as the distance to the next station unless that equivalence is established for the specific path.

## 3. Important semantic correction

The current analyzer computes:

`deviationSeconds = alignedClock - scheduledDeparture`

and labels a positive value as `late`. That is not a train delay measurement. During a normally progressing station interval, the clock naturally advances beyond the scheduled departure time. The value is elapsed time relative to the scheduled departure reference, not lateness.

Until a valid actual arrival/departure event or a validated physical-progress-to-schedule model exists:

- Do not label this value as `late` or `early`.
- Describe it as elapsed time from the scheduled departure reference, or as position in the scheduled time interval.
- A negative remaining time means the scheduled next-station event time has passed; by itself it does not prove that the train arrived late.
- Do not infer a delay from `moving=false`; it may represent a platform stop, signal/route blockage, or another stationary condition.

This terminology correction should be made before using `timing` output for delay verification.

## 4. Chosen design

### Reuse `timing` as the per-snapshot progress diagnostic

Do not add a second one-shot command that duplicates the stored-binding lookup and station-pair analysis. Extend the existing `timing` analysis and output to make the current bound-train segment explicit.

The eventual read-only result should include:

1. Binding identity: timetable ID, train index, and direction.
2. Pair match: current station and next station, plus the matched OuDia stop sequence/index.
3. Stop semantics: current and next stop types; distinguish scheduled stops from pass-through points.
4. Scheduled events: current departure/arrival reference and next arrival/departure event, including which fallback was used.
5. Clock: full local date/time derived from the MTR4 simulation timestamp, not only HH:mm:ss, so day rollover is visible.
6. Interval timing: elapsed time since the scheduled departure reference and time remaining/past the scheduled next-station event. Do not call these values actual delay.
7. MTR4 observations: moving flag, speed, rail progress, and next ATO-stop distance when available.
8. Match state: matched, bound train does not contain the current pair, missing schedule time, or unavailable MTR4 clock.

A bound train that no longer matches the current station pair must be reported as a mismatch. Never fall back to a different candidate automatically.

### Repeated observation for the first verification pass

Use repeated manual `/oudiabridge timing <vehicleId>` snapshots at meaningful transitions:

- while moving through a station interval;
- immediately before reaching the next station;
- after the current/next station pair changes;
- while stopped at a scheduled stop;
- after departure from that stop.

Record timestamps and command outputs. This establishes whether the stored train's stop sequence follows the MTR4 vehicle. It does not, on its own, establish exact arrival delay.

No background polling, automatic rebinding, vehicle timing changes, speed changes, door changes, or MTR4 lifecycle patches are part of this pass.

## 5. Later work, only after observations

If manual snapshots show a stable correspondence, consider a separate monitoring service only if continuous history is actually needed. That later design must define sampling frequency, lifecycle cleanup, world unload behavior, timetable reload behavior, and bounded log/storage policy before implementation.

Actual delay calculation is a separate later requirement. It needs a reliable actual station-arrival/departure observation or a validated physical progress model. Do not derive it from elapsed time since scheduled departure alone.

## 6. Verification acceptance criteria

- The same stored train index is used across all samples.
- The current/next station pair maps to the expected consecutive stops in the bound OuDia train.
- Stop/pass semantics and missing times are reported without guessing.
- Full local date/time remains correct across midnight.
- A pair mismatch is reported and never causes automatic rebinding.
- Repeated snapshots show the expected sequence of station pairs.
- MTR4 `useRealTime=true` remains enabled.
- MTR4 door cooldown, route-block checks, departure logic, speed, and vehicle position remain untouched.
