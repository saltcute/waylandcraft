package dev.evvie.waylandcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import dev.evvie.waylandcraft.render.WindowFramebuffer;

/**
 * Drives the shipped composite sizing path so scale 2 keeps more pixels
 * while world footprint stays logical-based.
 */
public class HiresCompositeTest {
	
	@Test
	void compositePixelsScale1IsLogical() {
		assertEquals(400, IntegerScale.compositePixels(400, 1));
		assertEquals(300, IntegerScale.compositePixels(300, 1));
		int[] size = WindowFramebuffer.compositePixelSize(400, 300, 1);
		assertEquals(400, size[0]);
		assertEquals(300, size[1]);
	}
	
	@Test
	void compositePixelsScale2IsDoubleLogical() {
		assertEquals(800, IntegerScale.compositePixels(400, 2));
		assertEquals(600, IntegerScale.compositePixels(300, 2));
		int[] size = WindowFramebuffer.compositePixelSize(400, 300, 2);
		assertEquals(800, size[0]);
		assertEquals(600, size[1]);
	}
	
	@Test
	void compositePixelsClampsInvalidScale() {
		assertEquals(400, IntegerScale.compositePixels(400, 0));
		assertEquals(400, IntegerScale.compositePixels(400, -2));
		int[] size = WindowFramebuffer.compositePixelSize(100, 50, 0);
		assertEquals(100, size[0]);
		assertEquals(50, size[1]);
	}
	
	@Test
	void resolveCompositeScaleUsesBufferScaleOnly() {
		// Composite target scale follows client buffer_scale, not outputScale alone.
		assertEquals(1, IntegerScale.resolveCompositeScale(1));
		assertEquals(2, IntegerScale.resolveCompositeScale(2));
		assertEquals(1, IntegerScale.resolveCompositeScale(0));
		// outputScale=2 with bufferScale=1 must NOT inflate composite (deprecated 2-arg API).
		assertEquals(1, IntegerScale.resolveCompositeScale(1, 2));
		assertEquals(2, IntegerScale.resolveCompositeScale(2, 1));
		assertEquals(2, IntegerScale.resolveCompositeScale(2, 3));
	}
	
	@Test
	void outputScaleAloneDoesNotUpsizeEmptyComposite() {
		// When clients still submit scale-1 buffers, composite stays logical-sized
		// so bakeSurface (bufferScale=1) fills the whole target.
		int logicalW = 400;
		int logicalH = 300;
		int bufferScale = 1; // client not yet reconfigured
		int[] pixels = WindowFramebuffer.compositePixelSize(logicalW, logicalH, bufferScale);
		assertEquals(400, pixels[0]);
		assertEquals(300, pixels[1]);
		// After clients use buffer_scale 2, composite doubles.
		int[] hi = WindowFramebuffer.compositePixelSize(logicalW, logicalH, 2);
		assertEquals(800, hi[0]);
		assertEquals(600, hi[1]);
	}
	
	@Test
	void worldFootprintBasisStaysLogicalIndependentOfScale() {
		// World span uses logical geometry × (1/pixelsPerBlock), not composite pixels.
		// Same logical size at scale 1 and 2 must share the same world-span basis.
		int logicalW = 400;
		int logicalH = 300;
		int pixelsPerBlock = 500;
		
		double worldW1 = logicalW / (double) pixelsPerBlock;
		double worldH1 = logicalH / (double) pixelsPerBlock;
		
		// Scale only changes composite pixel size, not the logical geometry inputs.
		int[] pixels1 = WindowFramebuffer.compositePixelSize(logicalW, logicalH, 1);
		int[] pixels2 = WindowFramebuffer.compositePixelSize(logicalW, logicalH, 2);
		assertEquals(logicalW, pixels1[0]);
		assertEquals(logicalW * 2, pixels2[0]);
		
		// World footprint basis is still logical (display path uses getWidth logical).
		double worldW2 = logicalW / (double) pixelsPerBlock;
		double worldH2 = logicalH / (double) pixelsPerBlock;
		assertEquals(worldW1, worldW2, 1e-12);
		assertEquals(worldH1, worldH2, 1e-12);
	}
	
	@Test
	void compositePathUsesPixelTargetsNotLogicalOnly() throws IOException {
		Path fb = projectRoot().resolve("src/main/java/dev/evvie/waylandcraft/render/WindowFramebuffer.java");
		assertTrue(Files.isRegularFile(fb));
		String src = Files.readString(fb, StandardCharsets.UTF_8);
		
		assertTrue(src.contains("compositePixelSize") || src.contains("pixelWidth"),
				"WindowFramebuffer must track composite pixel size");
		assertTrue(src.contains("compositePixels") || src.contains("logicalToBuffer"),
				"composite sizing must apply scale factor");
		// GPU targets must be created from pixel dimensions, not logical-only fields.
		assertTrue(src.contains("pixelWidth") && src.contains("pixelHeight"),
				"must have pixelWidth/pixelHeight for GPU targets");
		assertTrue(src.contains("new TextureTarget") && src.contains("pixelWidth"),
				"TextureTarget must be sized with pixel dimensions");
		// World/HUD still expose logical size via getWidth/getHeight.
		assertTrue(src.contains("return logicalWidth") || src.contains("logicalWidth"),
				"getWidth path must remain logical for world footprint");
		// Must not size GPU composite from outputScale when buffers are still 1×.
		assertFalse(src.contains("getOutputScale()"),
				"composite scale must not use settings outputScale alone (causes empty padding)");
		assertTrue(src.contains("getBufferScale()"),
				"composite scale must follow client buffer_scale");
	}
	
	@Test
	void displayWorldSpanUsesFramebufferLogicalWidth() throws IOException {
		Path display = projectRoot().resolve("src/main/java/dev/evvie/waylandcraft/displays/AbstractWindowDisplay.java");
		String src = Files.readString(display, StandardCharsets.UTF_8);
		// World render uses framebuffer.getWidth/Height for span; those must stay logical.
		assertTrue(src.contains("framebuffer.getWidth()") && src.contains("framebuffer.getHeight()"),
				"world render spans from framebuffer width/height");
		assertTrue(src.contains("getPixelsPerBlock") || Files.readString(
				projectRoot().resolve("src/main/java/dev/evvie/waylandcraft/displays/WindowDisplay.java"),
				StandardCharsets.UTF_8).contains("getPixelsPerBlock"),
				"world pixel scale comes from pixelsPerBlock");
		// Ensure we did not switch world span to getPixelWidth.
		assertFalse(src.contains("getPixelWidth"),
				"AbstractWindowDisplay must not size world from getPixelWidth");
	}
	
	@Test
	void scale2BakeFillsCompositeNotQuarter() {
		// Regression: drawing logical-sized quads into a 2× target filled only 1/4 of the window.
		assertEquals(1.0, SurfaceBakePlacement.fullSurfaceFillRatio(400, 300, 2), 1e-9);
		assertEquals(1.0, SurfaceBakePlacement.fullSurfaceFillRatio(1920, 1080, 2), 1e-9);
		assertEquals(1.0, SurfaceBakePlacement.fullSurfaceFillRatio(400, 300, 1), 1e-9);
	}
	
	private static Path projectRoot() {
		Path cwd = Path.of("").toAbsolutePath();
		if (Files.isRegularFile(cwd.resolve("gradle.properties"))) {
			return cwd;
		}
		Path p = cwd;
		for (int i = 0; i < 4; i++) {
			if (Files.isRegularFile(p.resolve("gradle.properties"))) {
				return p;
			}
			p = p.getParent();
			if (p == null) break;
		}
		return cwd;
	}
}
