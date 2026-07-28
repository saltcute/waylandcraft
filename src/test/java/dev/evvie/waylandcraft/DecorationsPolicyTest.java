package dev.evvie.waylandcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Policy + structural checks for decoration negotiation (Spotify/Electron CSD).
 */
public class DecorationsPolicyTest {
	
	@Test
	void alwaysClientSide() {
		assertEquals(DecorationsPolicy.Mode.CLIENT_SIDE, DecorationsPolicy.preferredMode());
		assertEquals(DecorationsPolicy.Mode.CLIENT_SIDE,
				DecorationsPolicy.resolveRequest(DecorationsPolicy.Mode.SERVER_SIDE));
		assertEquals(DecorationsPolicy.Mode.CLIENT_SIDE,
				DecorationsPolicy.resolveRequest(DecorationsPolicy.Mode.CLIENT_SIDE));
		assertFalse(DecorationsPolicy.supportsServerSide());
	}
	
	@Test
	void nativeAdvertisesDecorationGlobals() throws IOException {
		Path lib = projectRoot().resolve("native/src/lib.rs");
		String src = Files.readString(lib, StandardCharsets.UTF_8);
		assertTrue(src.contains("XdgDecorationState"),
				"must create zxdg_decoration_manager_v1 via XdgDecorationState");
		assertTrue(src.contains("delegate_xdg_decoration"),
				"must delegate xdg decoration protocol");
		assertTrue(src.contains("ClientSide") || src.contains("Mode::ClientSide"),
				"must configure ClientSide decorations");
		assertTrue(src.contains("KdeDecorationState") || src.contains("kde_decoration"),
				"must advertise KDE server-decoration global for Electron");
		assertTrue(src.contains("Mode::Client") || src.contains("KdeDefaultMode::Client"),
				"KDE default mode must be Client (CSD)");
	}
	
	/**
	 * Decoration path must match smithay anvil: pending mode + send_pending_configure
	 * on set_mode/unset_mode only. Forced send_configure() during decoration
	 * negotiation freezes the game when any window maps.
	 */
	@Test
	void nativeDecorationPathMatchesAnvil() throws IOException {
		Path lib = projectRoot().resolve("native/src/lib.rs");
		String src = Files.readString(lib, StandardCharsets.UTF_8);
		int helper = src.indexOf("fn prefer_client_side_decoration");
		assertTrue(helper >= 0, "must have prefer_client_side_decoration helper on live path");
		int bodyEnd = src.indexOf("\nimpl ", helper);
		if (bodyEnd < 0) bodyEnd = src.length();
		String helperBody = src.substring(helper, bodyEnd);
		assertTrue(helperBody.contains("send_pending_configure"),
				"decoration helper must use send_pending_configure (anvil pattern)");
		// Must not force unconditional send_configure inside the decoration helper.
		assertFalse(helperBody.contains("toplevel.send_configure()"),
				"decoration helper must not call send_configure() (freezes window map)");
		assertTrue(src.contains("prefer_client_side_decoration(&toplevel, true)"),
				"request_mode/unset_mode must request pending configure");
		assertTrue(src.contains("prefer_client_side_decoration(&toplevel, false)"),
				"new_decoration must not send configure");
	}
	
	private static Path projectRoot() {
		Path cwd = Path.of("").toAbsolutePath();
		if (Files.isRegularFile(cwd.resolve("gradle.properties"))) return cwd;
		Path p = cwd;
		for (int i = 0; i < 4; i++) {
			if (Files.isRegularFile(p.resolve("gradle.properties"))) return p;
			p = p.getParent();
			if (p == null) break;
		}
		return cwd;
	}
}
