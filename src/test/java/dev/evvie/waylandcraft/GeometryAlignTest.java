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
import dev.evvie.waylandcraft.render.WindowFramebuffer;

/**
 * Drives shipped {@link WindowGeometryMapping} used for CSD paint/pick alignment
 * (Firefox-style non-zero geometry origin).
 */
public class GeometryAlignTest {
	
	@Test
	void zeroGeometryMeansNoContentOffset() {
		SurfaceGeometry geo = WindowGeometryMapping.effectiveGeometry(null, 800, 600);
		assertEquals(0, geo.x());
		assertEquals(0, geo.y());
		assertEquals(800, geo.width());
		assertEquals(600, geo.height());
		
		assertEquals(0, WindowGeometryMapping.renderOffsetX(0, 0));
		assertEquals(0, WindowGeometryMapping.renderOffsetY(0, 0));
		assertEquals(0.0, WindowGeometryMapping.toSurfaceLocalX(0, 0), 0);
		assertTrue(WindowGeometryMapping.paintAndPickAgree(0, 0, geo));
	}
	
	@Test
	void csdGeometryOriginAlignsPaintAndPick() {
		// Firefox-like: shadow margin (24, 16) then content 752×568 on 800×600 surface.
		SurfaceGeometry geo = new SurfaceGeometry(24, 16, 752, 568);
		int treeXOff = 0;
		int treeYOff = 0;
		
		int placeX = WindowGeometryMapping.renderOffsetX(treeXOff, geo.x());
		int placeY = WindowGeometryMapping.renderOffsetY(treeYOff, geo.y());
		assertEquals(-24, placeX);
		assertEquals(-16, placeY);
		
		// Content top-left (geometry origin) is at display (0,0) after placement.
		assertEquals(0, treeXOff + geo.x() + placeX);
		assertEquals(0, treeYOff + geo.y() + placeY);
		
		// Pick at display (0,0) → surface-local (24, 16).
		assertEquals(24.0, WindowGeometryMapping.toSurfaceLocalX(0, geo.x()), 0);
		assertEquals(16.0, WindowGeometryMapping.toSurfaceLocalY(0, geo.y()), 0);
		
		// Inverse: surface (24, 16) → geometry local (0, 0).
		assertEquals(0.0, WindowGeometryMapping.toGeometryLocalX(24, geo.x()), 0);
		assertEquals(0.0, WindowGeometryMapping.toGeometryLocalY(16, geo.y()), 0);
		
		assertTrue(WindowGeometryMapping.paintAndPickAgree(treeXOff, treeYOff, geo));
	}
	
	@Test
	void csdWithTreeOffsetStillAgrees() {
		// Subsurface extends left of root (tree minX = -10 → xoff = 10).
		SurfaceGeometry geo = new SurfaceGeometry(30, 20, 700, 500);
		int treeXOff = 10;
		int treeYOff = 5;
		
		int placeX = WindowGeometryMapping.renderOffsetX(treeXOff, geo.x());
		int placeY = WindowGeometryMapping.renderOffsetY(treeYOff, geo.y());
		assertEquals(-40, placeX);
		assertEquals(-25, placeY);
		
		// Surface point under content origin after placement:
		assertEquals(0, treeXOff + geo.x() + placeX);
		assertEquals(0, treeYOff + geo.y() + placeY);
		assertTrue(WindowGeometryMapping.paintAndPickAgree(treeXOff, treeYOff, geo));
	}
	
	@Test
	void scale1And2LogicalGeometryMappingUnchanged() {
		// Mapping is logical-only; composite scale multiplies GPU targets separately.
		SurfaceGeometry geo = new SurfaceGeometry(12, 8, 400, 300);
		assertTrue(WindowGeometryMapping.paintAndPickAgree(0, 0, geo));
		
		// Scale 1 and 2 produce different pixel composites for the same logical geometry.
		int[] p1 = WindowFramebuffer.compositePixelSize(geo.width(), geo.height(), 1);
		int[] p2 = WindowFramebuffer.compositePixelSize(geo.width(), geo.height(), 2);
		assertEquals(400, p1[0]);
		assertEquals(800, p2[0]);
		// Content origin mapping itself does not change with scale.
		assertEquals(12.0, WindowGeometryMapping.toSurfaceLocalX(0, geo.x()), 0);
		assertEquals(12.0, WindowGeometryMapping.toSurfaceLocalX(0, geo.x()), 0);
	}
	
	@Test
	void displayPathsUseSharedMapping() throws IOException {
		Path root = projectRoot();
		String abs = Files.readString(root.resolve("src/main/java/dev/evvie/waylandcraft/displays/AbstractWindowDisplay.java"), StandardCharsets.UTF_8);
		String win = Files.readString(root.resolve("src/main/java/dev/evvie/waylandcraft/displays/WindowDisplay.java"), StandardCharsets.UTF_8);
		assertTrue(abs.contains("WindowGeometryMapping.renderOffsetX"),
				"render must use WindowGeometryMapping for CSD offset");
		assertTrue(win.contains("WindowGeometryMapping.toSurfaceLocalX"),
				"pick must use WindowGeometryMapping for CSD offset");
		// Old ad-hoc form should not remain as the sole path
		assertFalse(abs.contains("-xoff - geometryX") && !abs.contains("WindowGeometryMapping"),
				"must not use unshared -xoff - geometryX placement");
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
