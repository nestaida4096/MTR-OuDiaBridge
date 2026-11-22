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

## Diagnostics command
The permission-level-2 command `/oudiabridge trains [radius]` collects MTR4 vehicles from the current dimension. The default radius is 64 blocks and the maximum is 4096 blocks. Vehicle state is read through `Simulator.run` so that threaded MTR simulation is not accessed from the Minecraft server thread.

The runtime bridge currently resolves MTR's private `Init.main` and `Main.simulators` fields reflectively. This is an isolated compatibility boundary; it must be checked against the exact MTR release being tested. Vehicles whose head position cannot be resolved are reported as omitted rather than being incorrectly treated as nearby.

## Porting status
- Architectury common/Fabric/Forge structure: present
- MTR4 runtime access and nearby vehicle diagnostics: initial implementation
- Persistent OuDia-to-Vehicle association: pending
- MTR4 door/departure behavior: upstream code inspection indicates `Vehicle.startUp` closes doors and proceeds only when its private door cooldown reaches zero; no behavioral patch has been made without an observed MTR4 defect
- Fabric/Forge build and in-game verification: pending
