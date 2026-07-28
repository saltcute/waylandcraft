package dev.evvie.waylandcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import dev.evvie.waylandcraft.settings.WaylandCraftSettings;

/**
 * Drives the shipped integer-scale validation and logical/buffer mapping helpers.
 */
public class IntegerScaleTest {
	
	@Test
	void defaultAndClamp() {
		assertEquals(1, IntegerScale.DEFAULT);
		assertEquals(1, IntegerScale.clamp(1));
		assertEquals(2, IntegerScale.clamp(2));
		assertEquals(3, IntegerScale.clamp(3));
		assertEquals(1, IntegerScale.clamp(0));
		assertEquals(1, IntegerScale.clamp(-5));
		assertEquals(IntegerScale.MAX, IntegerScale.clamp(IntegerScale.MAX + 10));
		assertTrue(IntegerScale.isValid(1));
		assertTrue(IntegerScale.isValid(2));
		assertFalse(IntegerScale.isValid(0));
		assertFalse(IntegerScale.isValid(-1));
	}
	
	@Test
	void bufferLogicalMappingScale1IsIdentity() {
		assertEquals(100, IntegerScale.bufferToLogical(100, 1));
		assertEquals(100.0, IntegerScale.logicalToBuffer(100.0, 1), 1e-9);
		assertEquals(50.0, IntegerScale.bufferToLogicalCoord(50.0, 1), 1e-9);
	}
	
	@Test
	void bufferLogicalMappingScale2() {
		assertEquals(50, IntegerScale.bufferToLogical(100, 2));
		assertEquals(40, IntegerScale.bufferToLogical(80, 2));
		// odd sizes floor
		assertEquals(50, IntegerScale.bufferToLogical(101, 2));
		assertEquals(200.0, IntegerScale.logicalToBuffer(100.0, 2), 1e-9);
		assertEquals(25.0, IntegerScale.bufferToLogicalCoord(50.0, 2), 1e-9);
	}
	
	@Test
	void settingsDefaultOutputScaleIsOne() {
		WaylandCraftSettings settings = new WaylandCraftSettings();
		assertEquals(1, settings.getOutputScale());
		// Gson-style zero field (missing key) is normalized by getOutputScale clamp path:
		// set field via reflection to 0 and ensure getter still returns ≥ 1.
		try {
			var field = WaylandCraftSettings.class.getDeclaredField("outputScale");
			field.setAccessible(true);
			field.setInt(settings, 0);
			assertEquals(1, settings.getOutputScale());
			field.setInt(settings, 2);
			assertEquals(2, settings.getOutputScale());
			field.setInt(settings, -1);
			assertEquals(1, settings.getOutputScale());
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
		// Shipped setter clamps through IntegerScale (same function as setIntSetting uses)
		assertEquals(2, IntegerScale.clamp(2));
		assertEquals(1, IntegerScale.clamp(0));
	}
	
	@Test
	void outputPathNoLongerHardcodesOnlyScale1() throws IOException {
		Path root = projectRoot();
		Path outputRs = root.resolve("native/src/output.rs");
		assertTrue(Files.isRegularFile(outputRs), "output.rs must exist");
		String src = Files.readString(outputRs, StandardCharsets.UTF_8);
		
		// Must have scale state and advertise it (not only literal scale(1) with no path to other values).
		assertTrue(src.contains("scale:"), "WLCOutput must store scale state");
		assertTrue(src.contains("set_scale"), "must be able to change scale");
		assertTrue(src.contains("output.scale(") || src.contains("output.scale(self"),
				"must advertise scale on wl_output");
		// Hardcoded-only path was `output.scale(1)` with no set_scale — ensure set_scale can emit non-1.
		assertTrue(src.contains("self.scale"), "bind/update must use stored scale field");
		
		Path bridge = root.resolve("native/src/bridge.rs");
		String bridgeSrc = Files.readString(bridge, StandardCharsets.UTF_8);
		assertTrue(bridgeSrc.contains("output_set_scale") || bridgeSrc.contains("outputSetScale"),
				"JNI bridge must expose set scale");
		assertTrue(bridgeSrc.contains("output_scale") || bridgeSrc.contains("fn output_scale"),
				"JNI bridge must expose get scale");
		
		Path javaBridge = root.resolve("src/main/java/dev/evvie/waylandcraft/bridge/WaylandCraftBridge.java");
		String java = Files.readString(javaBridge, StandardCharsets.UTF_8);
		assertTrue(java.contains("setOutputScale"), "Java bridge must expose setOutputScale");
		assertTrue(java.contains("getOutputScale"), "Java bridge must expose getOutputScale");
	}
	
	@Test
	void surfaceLogicalSizeUsesBufferScale() throws IOException {
		Path surface = projectRoot().resolve("src/main/java/dev/evvie/waylandcraft/bridge/WLCSurface.java");
		String src = Files.readString(surface, StandardCharsets.UTF_8);
		assertTrue(src.contains("bufferScale") || src.contains("setBufferScale"),
				"WLCSurface must track client buffer_scale");
		assertTrue(src.contains("IntegerScale.bufferToLogical") || src.contains("setLogicalSize"),
				"WLCSurface must convert buffer size to logical size");
		// Viewport src from smithay is already Logical — must not divide by bufferScale again.
		int setViewportSrcIdx = src.indexOf("void setViewportSrc");
		assertTrue(setViewportSrcIdx >= 0, "setViewportSrc must exist");
		int nextMethod = src.indexOf("void setViewportDst", setViewportSrcIdx);
		assertTrue(nextMethod > setViewportSrcIdx, "setViewportDst must follow setViewportSrc");
		String viewportSrcBody = src.substring(setViewportSrcIdx, nextMethod);
		assertFalse(viewportSrcBody.contains("bufferToLogical"),
				"setViewportSrc must not re-apply bufferToLogical on already-logical src");
		assertTrue(viewportSrcBody.contains("this.width = (int) width")
						|| viewportSrcBody.contains("this.width=(int)width"),
				"setViewportSrc must use src size as logical size");
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

	/**
	 * update_surface_data holds SurfaceAttributes mutex; try_attach_* must receive
	 * buffer_scale as a parameter and must not call cached_state.get again (deadlock).
	 */
	@Test
	void attachPathMustNotRelockSurfaceAttributes() throws Exception {
		Path bridge = projectRoot().resolve("native/src/bridge.rs");
		String src = Files.readString(bridge);
		int shm = src.indexOf("fn try_attach_shm");
		int single = src.indexOf("fn try_attach_single_pixel");
		assertTrue(shm >= 0 && single > shm);
		String shmBody = src.substring(shm, single);
		assertFalse(shmBody.contains("cached_state.get::<SurfaceAttributes>"),
				"try_attach_shm must not re-lock SurfaceAttributes (render-thread deadlock)");
		assertTrue(shmBody.contains("buffer_scale"),
				"try_attach_shm must take buffer_scale from caller");
		int dma = src.indexOf("fn try_attach_dmabuf");
		// Next function after try_attach_dmabuf (not the earlier bind registration of dmabufs).
		int afterDma = src.indexOf("\nfn ", dma + 1);
		assertTrue(dma > single && afterDma > dma);
		String dmaBody = src.substring(dma, afterDma);
		assertFalse(dmaBody.contains("cached_state.get::<SurfaceAttributes>"),
				"try_attach_dmabuf must not re-lock SurfaceAttributes");
		assertTrue(src.contains("try_attach_buffer(instance, env, &jsurface, buf, data, buffer_scale)"),
				"call site must pass buffer_scale into try_attach_buffer");
	}

}
