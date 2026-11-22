# MTR-OuDiaBridge — MTR4

An isolated Architectury multi-loader project. The existing MTR3 project remains at the repository root and is not modified by this subproject.

## Initial target
- Minecraft 1.20.4
- MTR4
- Fabric and MinecraftForge
- Java 17
- Architectury API 11.1.17

## Layout
- common: shared timetable, operation-state, and diagnostics logic
- fabric: Fabric entry point and platform integrations
- forge: Forge entry point and platform integrations

## Build
Run from the repository root:
```powershell
.\gradlew.bat -p mtr4 build
```

This initial commit establishes the loader split and initialization skeleton only. MTR4 vehicle/timetable integration, nearby-train diagnostics, and door-departure behavior have not yet been ported. These features must be implemented against MTR4's `org.mtr.core.data.Vehicle` API and verified independently on both loaders.

Only Minecraft 1.20.4 is configured at this stage. Other MTR4 versions are not yet claimed as supported.
