package dev.evvie.waylandcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import dev.evvie.waylandcraft.render.WindowFramebuffer;

/**
 * Drives shipped {@link SurfaceBakePlacement} so scale-2 composites fill fully
 * (no quarter-fill) and UVs map logical viewport → buffer correctly.
 */
public class SurfaceBakePlacementTest {
	
	@Test
	void scale1PlacementIsLogicalIdentity() {
		SurfaceBakePlacement.BakeQuad q = SurfaceBakePlacement.place(
				10, 20, 400, 300, 1, 400, 300, 1, false, 0, 0, 0, 0);
		assertEquals(10f, q.x(), 1e-5);
		assertEquals(20f, q.y(), 1e-5);
		assertEquals(400f, q.w(), 1e-5);
		assertEquals(300f, q.h(), 1e-5);
		assertEquals(0f, q.u1(), 1e-5);
		assertEquals(1f, q.u2(), 1e-5);
	}
	
	@Test
	void scale2PlacementFillsCompositeNotQuarter() {
		int logicalW = 400;
		int logicalH = 300;
		int scale = 2;
		// Full surface at origin must cover the entire composite target.
		assertEquals(1.0, SurfaceBakePlacement.fullSurfaceFillRatio(logicalW, logicalH, scale), 1e-9);
		assertEquals(1.0, SurfaceBakePlacement.fullSurfaceFillRatio(logicalW, logicalH, 1), 1e-9);
		
		int[] comp = WindowFramebuffer.compositePixelSize(logicalW, logicalH, scale);
		SurfaceBakePlacement.BakeQuad q = SurfaceBakePlacement.place(
				0, 0, logicalW, logicalH, scale, comp[0], comp[1], scale, false, 0, 0, 0, 0);
		assertEquals(comp[0], q.w(), 1e-5);
		assertEquals(comp[1], q.h(), 1e-5);
		// Wrong placement (logical into 2× target) would be quarter area:
		double wrongQuarter = (logicalW * (double) logicalH) / (comp[0] * (double) comp[1]);
		assertEquals(0.25, wrongQuarter, 1e-9);
		assertTrue(SurfaceBakePlacement.fullSurfaceFillRatio(logicalW, logicalH, scale) > 0.99);
	}
	
	@Test
	void placementUsesCompositeScaleNotBufferScaleAlone() {
		// Composite is scale 2; surface buffer_scale also 2 — place with composite scale.
		SurfaceBakePlacement.BakeQuad q = SurfaceBakePlacement.place(
				0, 0, 100, 50, 2, 200, 100, 2, false, 0, 0, 0, 0);
		assertEquals(200f, q.w(), 1e-5);
		assertEquals(100f, q.h(), 1e-5);
	}
	
	@Test
	void viewportUvUsesBufferScaleOnLogicalSrc() {
		// Logical crop (0,0)-(100,50) on scale-2 buffer 200×100 → full UV 0-1.
		SurfaceBakePlacement.BakeQuad q = SurfaceBakePlacement.place(
				0, 0, 100, 50, 2, 200, 100, 2, true, 0, 0, 100, 50);
		assertEquals(0f, q.u1(), 1e-5);
		assertEquals(0f, q.v1(), 1e-5);
		assertEquals(1f, q.u2(), 1e-5);
		assertEquals(1f, q.v2(), 1e-5);
		// Half crop in logical space
		SurfaceBakePlacement.BakeQuad half = SurfaceBakePlacement.place(
				0, 0, 100, 50, 2, 200, 100, 2, true, 0, 0, 50, 25);
		assertEquals(0.5f, half.u2(), 1e-5);
		assertEquals(0.5f, half.v2(), 1e-5);
	}
	
	@Test
	void bakedSurfacePathUsesSharedPlacementHelper() throws IOException {
		Path fb = projectRoot().resolve("src/main/java/dev/evvie/waylandcraft/render/WindowFramebuffer.java");
		String src = Files.readString(fb, StandardCharsets.UTF_8);
		assertTrue(src.contains("SurfaceBakePlacement.place"),
				"bakeSurface must call SurfaceBakePlacement.place");
		assertTrue(src.contains("this.compositeScale") || src.contains("compositeScale"),
				"bake must place using composite scale");
		// Must not size draw rect from surface buffer_scale alone while composite differs
		// (historical quarter-fill when only one path applied scale).
		int bake = src.indexOf("private BufferDraw bakeSurface");
		assertTrue(bake >= 0);
		int end = src.indexOf("private static record CompiledBufferDraw", bake);
		if(end < 0) end = src.length();
		String body = src.substring(bake, end);
		assertTrue(body.contains("SurfaceBakePlacement"),
				"bakeSurface body must use SurfaceBakePlacement");
		assertTrue(body.contains("compositeScale"),
				"bakeSurface must pass compositeScale into placement");
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
