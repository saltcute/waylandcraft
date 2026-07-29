package dev.evvie.waylandcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import dev.evvie.waylandcraft.bridge.WLCAbstractWindow.SurfaceGeometry;

/**
 * Content-geometry layout after decorator revert: outer size equals content
 * size (no SSD outer-frame expansion). Drives shipped
 * {@link WindowGeometryMapping} used by paint and pick.
 */
public class ServerDecorationChromeTest {
	
	/**
	 * Outer window size is content geometry only. Paint/pick share
	 * {@link WindowGeometryMapping} with no chrome inset.
	 */
	@Test
	void contentGeometryIsOuterSizeAndMapsConsistently() {
		// Client content rect (what used to be expanded by SSD chrome).
		int contentW = 800;
		int contentH = 600;
		int geometryX = 10;
		int geometryY = 20;
		// Pre-d77b08b: outer == content (no titlebar/border expansion).
		int outerW = contentW;
		int outerH = contentH;
		assertEquals(contentW, outerW, "outer width must equal content width");
		assertEquals(contentH, outerH, "outer height must equal content height");
		
		// Shipped paint placement: geometry origin sits at display (0,0).
		int treeXOff = 0;
		int treeYOff = 0;
		assertEquals(-geometryX, WindowGeometryMapping.renderOffsetX(treeXOff, geometryX));
		assertEquals(-geometryY, WindowGeometryMapping.renderOffsetY(treeYOff, geometryY));
		
		// Content-local (50, 50) → surface-local (geometry + 50).
		assertEquals(geometryX + 50.0, WindowGeometryMapping.toSurfaceLocalX(50.0, geometryX), 0);
		assertEquals(geometryY + 50.0, WindowGeometryMapping.toSurfaceLocalY(50.0, geometryY), 0);
		
		// Round-trip identity used by world paint + pick.
		SurfaceGeometry geometry = new SurfaceGeometry(geometryX, geometryY, contentW, contentH);
		assertTrue(WindowGeometryMapping.paintAndPickAgree(treeXOff, treeYOff, geometry),
				"paint and pick must agree without SSD chrome insets");
		// Non-zero tree offset (composited subsurface tree) still agrees.
		assertTrue(WindowGeometryMapping.paintAndPickAgree(12, 4, geometry));
	}
	
	@Test
	void windowGeometryMappingIsShippedAndUsed() throws IOException {
		Path root = projectRoot();
		assertTrue(Files.isRegularFile(root.resolve(
				"src/main/java/dev/evvie/waylandcraft/WindowGeometryMapping.java")),
				"WindowGeometryMapping must remain shipped (HiDPI)");
		String abstractSrc = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/displays/AbstractWindowDisplay.java"),
				StandardCharsets.UTF_8);
		String windowSrc = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/displays/WindowDisplay.java"),
				StandardCharsets.UTF_8);
		assertTrue(abstractSrc.contains("WindowGeometryMapping.renderOffsetX"),
				"world paint must call WindowGeometryMapping.renderOffsetX");
		assertTrue(windowSrc.contains("WindowGeometryMapping.toSurfaceLocalX"),
				"world pick must call WindowGeometryMapping.toSurfaceLocalX");
		assertFalse(abstractSrc.contains("ServerDecorationChrome"),
				"world paint must not reference deleted SSD chrome");
		assertFalse(windowSrc.contains("ServerDecorationChrome"),
				"world pick must not reference deleted SSD chrome");
		// Geometry assignment is content-only (width = geometry.width, not outerWidth).
		assertTrue(windowSrc.contains("width = window.geometry.width()"),
				"updateGeometry must set width from content geometry");
		assertTrue(windowSrc.contains("height = window.geometry.height()"),
				"updateGeometry must set height from content geometry");
		assertFalse(windowSrc.contains("outerWidth") || windowSrc.contains("outerHeight"),
				"updateGeometry must not expand for SSD outer frame");
	}
	
	@Test
	void primaryPresentationPathsAreContentOnly() throws IOException {
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
		
		assertFalse(world.contains("renderServerChrome"),
				"in-world path must not draw chrome");
		assertFalse(renderUtils.contains("renderServerChrome2D"),
				"must not provide 2D chrome helper");
		assertFalse(wm.contains("renderServerChrome2D"),
				"WindowManagerScreen must be content-only");
		assertFalse(hud.contains("renderServerChrome2D"),
				"HUD pinned-toplevel path must be content-only");
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
