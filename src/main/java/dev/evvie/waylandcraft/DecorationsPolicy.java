package dev.evvie.waylandcraft;

/**
 * Compositor decoration policy. WaylandCraft paints server-side system chrome
 * (titlebar + frame) for mapped windows, so negotiation settles on
 * server-side / system decoration mode.
 *
 * <p>Mirrors the native {@code XdgDecorationHandler} / KDE decoration handlers
 * that configure ServerSide / Server mode.
 */
public final class DecorationsPolicy {
	
	public enum Mode {
		CLIENT_SIDE,
		SERVER_SIDE
	}
	
	private DecorationsPolicy() {}
	
	/** Default / preferred mode advertised to clients. */
	public static Mode preferredMode() {
		return Mode.SERVER_SIDE;
	}
	
	/**
	 * Resolve a client mode request. Always returns {@link Mode#SERVER_SIDE}
	 * so clients that expect SSD (Spotify/Electron) get compositor chrome.
	 */
	public static Mode resolveRequest(Mode clientRequest) {
		return Mode.SERVER_SIDE;
	}
	
	/** True if the compositor will honor server-side decorations. */
	public static boolean supportsServerSide() {
		return true;
	}
	
}
