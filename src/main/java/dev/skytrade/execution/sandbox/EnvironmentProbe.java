package dev.skytrade.execution.sandbox;

/** Read-only view of where the game client currently is. */
public interface EnvironmentProbe {
    enum Location { MAIN_MENU, SINGLEPLAYER, MULTIPLAYER, UNKNOWN }

    Location location();

    /** Server address when in multiplayer, otherwise null. */
    String serverAddress();

    /** Server brand reported by the server (may be null). */
    String serverBrand();
}
