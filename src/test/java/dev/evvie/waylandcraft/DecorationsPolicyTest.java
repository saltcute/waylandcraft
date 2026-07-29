package dev.evvie.waylandcraft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Structural checks for pre-d77b08b decorator policy: no xdg/KDE decoration
 * globals, no ServerSide/ClientSide negotiation, no SSD chrome types on the
 * shipped path.
 */
public class DecorationsPolicyTest {
	
	@Test
	void nativeDoesNotAdvertiseDecorationGlobals() throws IOException {
		Path lib = projectRoot().resolve("native/src/lib.rs");
		String src = Files.readString(lib, StandardCharsets.UTF_8);
		assertFalse(src.contains("XdgDecorationState"),
				"must not create zxdg_decoration_manager_v1 via XdgDecorationState");
		assertFalse(src.contains("delegate_xdg_decoration"),
				"must not delegate xdg decoration protocol");
		assertFalse(src.contains("KdeDecorationState"),
				"must not advertise KDE server-decoration global");
		assertFalse(src.contains("delegate_kde_decoration"),
				"must not delegate KDE decoration protocol");
		assertFalse(src.contains("XdgDecorationHandler"),
				"must not implement XdgDecorationHandler");
		assertFalse(src.contains("KdeDecorationHandler"),
				"must not implement KdeDecorationHandler");
		assertFalse(src.contains("prefer_server_side_decoration"),
				"must not force ServerSide decoration mode");
		assertFalse(src.contains("prefer_client_side_decoration"),
				"must not force ClientSide decoration mode");
		assertFalse(src.contains("decoration_mode"),
				"must not set xdg decoration_mode on toplevel state");
	}
	
	@Test
	void decoratorOnlyJavaTypesAreNotShipped() {
		Path root = projectRoot();
		assertFalse(Files.isRegularFile(root.resolve(
				"src/main/java/dev/evvie/waylandcraft/ServerDecorationChrome.java")),
				"ServerDecorationChrome must not ship");
		assertFalse(Files.isRegularFile(root.resolve(
				"src/main/java/dev/evvie/waylandcraft/DecorationsPolicy.java")),
				"DecorationsPolicy must not ship");
	}
	
	@Test
	void displayPathsDoNotDrawSsdChrome() throws IOException {
		Path root = projectRoot();
		String abstractSrc = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/displays/AbstractWindowDisplay.java"),
				StandardCharsets.UTF_8);
		String windowSrc = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/displays/WindowDisplay.java"),
				StandardCharsets.UTF_8);
		String wm = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/gui/WindowManagerScreen.java"),
				StandardCharsets.UTF_8);
		String hud = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/gui/WaylandHudRenderer.java"),
				StandardCharsets.UTF_8);
		String renderUtils = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/render/RenderUtils.java"),
				StandardCharsets.UTF_8);
		
		assertFalse(abstractSrc.contains("ServerDecorationChrome")
				|| abstractSrc.contains("renderServerChrome")
				|| abstractSrc.contains("usesServerChrome"),
				"world display must not draw SSD chrome");
		assertFalse(windowSrc.contains("ServerDecorationChrome")
				|| windowSrc.contains("outerWidth")
				|| windowSrc.contains("isInChrome"),
				"window pick/layout must not expand for SSD chrome");
		assertFalse(wm.contains("ServerDecorationChrome")
				|| wm.contains("renderServerChrome2D"),
				"WindowManagerScreen must not paint SSD chrome");
		assertFalse(hud.contains("ServerDecorationChrome")
				|| hud.contains("renderServerChrome2D"),
				"HUD path must not paint SSD chrome");
		assertFalse(renderUtils.contains("renderServerChrome")
				|| renderUtils.contains("renderServerChrome2D"),
				"RenderUtils must not provide SSD chrome helpers");
		// HiDPI geometry mapping retained on the live path.
		assertTrue(abstractSrc.contains("WindowGeometryMapping"),
				"world path must keep WindowGeometryMapping");
		assertTrue(windowSrc.contains("WindowGeometryMapping"),
				"pick path must keep WindowGeometryMapping");
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
