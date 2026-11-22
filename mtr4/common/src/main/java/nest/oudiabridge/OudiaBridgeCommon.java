package nest.oudiabridge;

/** Shared MTR4 initialization entry point. */
public final class OudiaBridgeCommon {
    private static boolean initialized;
    private OudiaBridgeCommon() {}
    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        System.out.println("[MTR-OuDiaBridge/MTR4] Common initialization complete.");
    }
}
