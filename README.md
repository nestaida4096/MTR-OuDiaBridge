# MTR-OuDiaBridge — MTR4

Architectury multi-loader implementation for MTR4.

## Target
- Minecraft 1.20.4
- MTR 4.0.5 or later within the 1.20.4 line
- Fabric and MinecraftForge
- Java 17
- Architectury API 11.1.17

## Build
Run from the repository root:
```powershell
.\gradlew.bat build
```

## Commands
Both commands require permission level 2.

- `/oudiabridge reload`: loads every `.oud2` file in `config/oudiabridge/timetables/` on the server. The file name without extension becomes the timetable ID.
- `/oudiabridge timetables`: lists loaded timetable IDs, route names, train counts, and station counts.
- `/oudiabridge trains [radius]`: lists vehicles in the current dimension within the specified radius. The default is 64 blocks and the maximum is 4096 blocks.
- `/oudiabridge bind <vehicleId> <timetableId> <trainIndex>`: manually binds a vehicle to a train. The train index is zero-based. A stored manual binding takes priority over automatic matching.
- `/oudiabridge unbind <vehicleId>`: removes a stored binding.

Vehicle state is collected through `Simulator.run` to avoid reading threaded MTR simulation state from the Minecraft server thread. Vehicles whose head position cannot be resolved are explicitly omitted from the radius results.

The runtime bridge resolves MTR's private `Init.main` and `Main.simulators` fields reflectively. This is isolated in `Mtr4RuntimeAccess` and must be checked against the exact MTR release being tested.

## OuDia-to-vehicle association
The matcher requires an exact normalized route-name match and a unique current/next station sequence. Configured MTR station names from the parser's station mappings are preferred over the original OuDia station names. Ambiguous matches are not bound automatically; use `/oudiabridge bind` to resolve them. UTF-8 and Windows-31J (CP932) `.oud2` files are supported.

Successful matches are stored in per-dimension Minecraft SavedData under the MTR vehicle ID. The binding API is exposed through `OudiaVehicleBindingService`. The diagnostics command attempts matching for each nearby vehicle after timetables have been reloaded.

## Door and departure behavior
See [docs/MTR4_DOOR_DEPARTURE.md](docs/MTR4_DOOR_DEPARTURE.md). MTR4 and Transport-Simulation-Core are treated as immutable dependencies. OuDia departure times are targets; a delay of a few seconds is acceptable, and the add-on must not bypass MTR4 door cooldown or route-block checks.

## Verification status
CI builds for Fabric and Forge are being used to check compilation. In-game runtime verification still requires testing on both loaders with MTR 4.0.5. Source inspection and CI compilation do not establish runtime correctness.
