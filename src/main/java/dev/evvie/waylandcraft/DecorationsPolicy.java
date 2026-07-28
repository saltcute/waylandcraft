package dev.evvie.waylandcraft;

/**
 * Compositor decoration policy. WaylandCraft does not paint server-side
 * titlebars; clients must use client-side decorations (CSD).
 *
 * <p>Mirrors the native {@code XdgDecorationHandler} / KDE decoration handlers
 * that always configure ClientSide / Client mode.
 */
public final class DecorationsPolicy {
	
	public enum Mode {
		CLIENT_SIDE,
		SERVER_SIDE
	}
	
	private DecorationsPolicy() {}
	
	/** Default / preferred mode advertised to clients. */
	public static Mode preferredMode() {
		return Mode.CLIENT_SIDE;
	}
	
	/**
	 * Resolve a client mode request. Always returns {@link Mode#CLIENT_SIDE}
	 * because SSD is not implemented (avoids Spotify/Electron empty chrome).
	 */
	public static Mode resolveRequest(Mode clientRequest) {
		return Mode.CLIENT_SIDE;
	}
	
	/** True if the compositor will honor server-side decorations. */
	public static boolean supportsServerSide() {
		return false;
	}
	
}
