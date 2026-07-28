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
 * Drives shipped {@link ServerDecorationChrome} layout used by world + 2D
 * paint/pick paths for SSD-mapped windows.
 */
public class ServerDecorationChromeTest {
	
	@Test
	void chromeInsetsAreNonZero() {
		ServerDecorationChrome.Insets insets = ServerDecorationChrome.insets();
		assertTrue(insets.top() > 0, "titlebar height must be non-zero");
		assertTrue(insets.left() > 0 && insets.right() > 0 && insets.bottom() > 0,
				"frame borders must be non-zero");
		assertEquals(ServerDecorationChrome.TITLEBAR_HEIGHT, insets.top());
		assertEquals(ServerDecorationChrome.BORDER, insets.left());
	}
	
	@Test
	void outerFrameExpandsContent() {
		int contentW = 800;
		int contentH = 600;
		int outerW = ServerDecorationChrome.outerWidth(contentW);
		int outerH = ServerDecorationChrome.outerHeight(contentH);
		assertTrue(outerW > contentW);
		assertTrue(outerH > contentH);
		assertEquals(contentW, ServerDecorationChrome.contentWidth(outerW));
		assertEquals(contentH, ServerDecorationChrome.contentHeight(outerH));
		assertEquals(contentW + ServerDecorationChrome.insets().horizontal(), outerW);
		assertEquals(contentH + ServerDecorationChrome.insets().vertical(), outerH);
	}
	
	@Test
	void contentOriginSitsBelowTitlebar() {
		assertEquals(ServerDecorationChrome.BORDER, ServerDecorationChrome.contentOffsetX());
		assertEquals(ServerDecorationChrome.TITLEBAR_HEIGHT, ServerDecorationChrome.contentOffsetY());
		assertTrue(ServerDecorationChrome.contentOffsetY() > ServerDecorationChrome.contentOffsetX());
	}
	
	@Test
	void outerOriginMatchesContentInset() {
		int contentOriginX = 100;
		int contentOriginY = 200;
		assertEquals(contentOriginX - ServerDecorationChrome.contentOffsetX(),
				ServerDecorationChrome.outerOriginX(contentOriginX));
		assertEquals(contentOriginY - ServerDecorationChrome.contentOffsetY(),
				ServerDecorationChrome.outerOriginY(contentOriginY));
		// Round-trip: outer + inset = content
		int outerX = ServerDecorationChrome.outerOriginX(contentOriginX);
		assertEquals(contentOriginX, outerX + ServerDecorationChrome.contentOffsetX());
	}
	
	@Test
	void chromeVsContentHitRegions() {
		int cw = 400;
		int ch = 300;
		// Titlebar band
		assertTrue(ServerDecorationChrome.isInTitlebar(10, 5, cw));
		assertTrue(ServerDecorationChrome.isInChrome(10, 5, cw, ch));
		// Left border
		assertTrue(ServerDecorationChrome.isInChrome(1, ServerDecorationChrome.TITLEBAR_HEIGHT + 10, cw, ch));
		// Content interior
		double cx = ServerDecorationChrome.contentOffsetX() + 50;
		double cy = ServerDecorationChrome.contentOffsetY() + 50;
		assertFalse(ServerDecorationChrome.isInChrome(cx, cy, cw, ch));
		assertFalse(ServerDecorationChrome.isInTitlebar(cx, cy, cw));
		// Content-local mapping
		assertEquals(50.0, ServerDecorationChrome.toContentLocalX(cx), 0);
		assertEquals(50.0, ServerDecorationChrome.toContentLocalY(cy), 0);
	}
	
	@Test
	void activeWhenPolicySupportsServerSide() {
		assertTrue(DecorationsPolicy.supportsServerSide());
		assertTrue(ServerDecorationChrome.isActive());
	}
	
	@Test
	void worldDisplayPathInvokesChromeDrawing() throws IOException {
		Path root = projectRoot();
		String abstractSrc = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/displays/AbstractWindowDisplay.java"),
				StandardCharsets.UTF_8);
		String windowSrc = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/displays/WindowDisplay.java"),
				StandardCharsets.UTF_8);
		String renderUtils = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/render/RenderUtils.java"),
				StandardCharsets.UTF_8);
		
		assertTrue(
				abstractSrc.contains("ServerDecorationChrome") || windowSrc.contains("ServerDecorationChrome"),
				"world display path must reference ServerDecorationChrome");
		assertTrue(
				abstractSrc.contains("renderServerChrome") || windowSrc.contains("renderServerChrome"),
				"world path must invoke 3D chrome drawing helper");
		assertTrue(renderUtils.contains("renderServerChrome"),
				"RenderUtils must provide 3D chrome drawing");
	}
	
	/**
	 * Gate: world-only chrome is insufficient. Window-manager 2D blit and HUD
	 * pinned toplevel must also draw SSD chrome when active.
	 */
	@Test
	void allPrimaryPresentationPathsDrawChrome() throws IOException {
		Path root = projectRoot();
		String world = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/displays/AbstractWindowDisplay.java"),
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
		
		assertTrue(world.contains("renderServerChrome"),
				"in-world mapped-window path must draw chrome");
		assertTrue(renderUtils.contains("renderServerChrome2D"),
				"must provide 2D chrome helper for GUI paths");
		assertTrue(wm.contains("renderServerChrome2D"),
				"WindowManagerScreen must draw SSD chrome (world-only fails this gate)");
		assertTrue(wm.contains("ServerDecorationChrome"),
				"WindowManagerScreen must use shared chrome layout");
		assertTrue(wm.contains("isInChrome") || wm.contains("shouldDrawForToplevel"),
				"WindowManagerScreen pointer path must honor chrome insets");
		assertTrue(hud.contains("renderServerChrome2D"),
				"HUD pinned-toplevel path must draw SSD chrome");
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
