package dev.evvie.waylandcraft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.evvie.waylandcraft.bridge.WLCAbstractWindow;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.displays.WindowDisplay;
import dev.evvie.waylandcraft.displays.WindowDisplay.DisplayHitResult;
import dev.evvie.waylandcraft.settings.WaylandCraftSettings;
import net.minecraft.world.phys.Vec3;

/**
 * Drives shipped grab-hotkey target resolution: look-at / mouse-hover window,
 * or no-op when nothing is looked at. Does not reimplement the resolve oracle.
 */
public class GrabHotkeyLookTargetTest {
	
	private WaylandCraft previousInstance;
	
	@BeforeEach
	void installInstance() {
		previousInstance = WaylandCraft.instance;
	}
	
	@AfterEach
	void restoreInstance() {
		WaylandCraft.instance = previousInstance;
	}
	
	@Test
	void resolveReturnsNullWhenNoHover() {
		assertNull(WaylandCraft.resolveGrabHotkeyTarget(null));
	}
	
	@Test
	void resolveReturnsHoveredDisplayWhenPresent() {
		installCraft(new WaylandCraft());
		WindowDisplay display = syntheticDisplay(1L);
		DisplayHitResult hit = syntheticHit(display);
		assertSame(display, WaylandCraft.resolveGrabHotkeyTarget(hit));
	}
	
	@Test
	void applyGrabHotkeyNoOpsWhenHoverAbsent() {
		TrackingCraft craft = new TrackingCraft();
		installCraft(craft);
		
		assertNull(craft.applyGrabHotkey(null));
		assertFalse(craft.grabStarted);
		assertNull(craft.grabbedDisplay);
	}
	
	@Test
	void applyGrabHotkeyStartsExclusiveOnLookAtDisplay() {
		TrackingCraft craft = new TrackingCraft();
		installCraft(craft);
		
		WindowDisplay lookedAt = syntheticDisplay(7L);
		WindowDisplay other = syntheticDisplay(8L);
		// Focus/other windows must not matter — only hover does.
		craft.displays.add(other);
		craft.displays.add(lookedAt);
		
		DisplayHitResult hit = syntheticHit(lookedAt);
		WindowDisplay result = craft.applyGrabHotkey(hit);
		
		assertSame(lookedAt, result);
		assertTrue(craft.grabStarted);
		assertSame(lookedAt, craft.grabbedDisplay);
	}
	
	@Test
	void startWindowGrabUsesLiveHoveredDisplayOnly() {
		TrackingCraft craft = new TrackingCraft();
		installCraft(craft);
		
		WindowDisplay lookedAt = syntheticDisplay(3L);
		craft.hoveredDisplay = syntheticHit(lookedAt);
		
		// Shipped hotkey entry (minecraft unused for look-at path).
		craft.startWindowGrab(null);
		
		assertTrue(craft.grabStarted);
		assertSame(lookedAt, craft.grabbedDisplay);
		
		// Clear hover → no-op (does not fall back to focus).
		craft.grabStarted = false;
		craft.grabbedDisplay = null;
		craft.hoveredDisplay = null;
		craft.startWindowGrab(null);
		assertFalse(craft.grabStarted);
		assertNull(craft.grabbedDisplay);
	}
	
	@Test
	void structuralHotkeyUsesHoverNotMostRecentFocus() throws IOException {
		Path root = projectRoot();
		String wlc = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/WaylandCraft.java"),
				StandardCharsets.UTF_8);
		String wm = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/gui/WindowManagerScreen.java"),
				StandardCharsets.UTF_8);
		
		// Hotkey path resolves from hover.
		assertTrue(wlc.contains("resolveGrabHotkeyTarget") || wlc.contains("hoveredDisplay"),
				"grab hotkey must resolve from hover / look-at standard");
		assertTrue(wlc.contains("applyGrabHotkey(hoveredDisplay)")
						|| (wlc.contains("startWindowGrab") && wlc.contains("hoveredDisplay")),
				"startWindowGrab must pass live hoveredDisplay");
		
		// Isolate startWindowGrab body: no focus fallback.
		int start = wlc.indexOf("void startWindowGrab");
		assertTrue(start >= 0);
		int next = wlc.indexOf("void toggleFollowLock", start);
		if(next < 0) next = wlc.indexOf("\n\tvoid ", start + 10);
		if(next < 0) next = Math.min(wlc.length(), start + 1200);
		String grabHotkeyRegion = wlc.substring(start, next);
		assertFalse(grabHotkeyRegion.contains("getMostRecentFocus"),
				"grab hotkey must not use most-recent focus");
		assertFalse(grabHotkeyRegion.contains("getMostToLeastRecentFocus"),
				"grab hotkey must not fall back to focus order");
		assertTrue(grabHotkeyRegion.contains("hoveredDisplay")
						|| grabHotkeyRegion.contains("applyGrabHotkey"),
				"grab hotkey region must use hover path");
		
		// WM Grab button still uses focused toplevel (unchanged).
		assertTrue(wm.contains("onGrabPressed") && wm.contains("new WindowGrab")
						&& wm.contains("focused"),
				"WM Grab button must still act on focused/selected toplevel");
	}
	
	private static void installCraft(WaylandCraft craft) {
		WaylandCraft.instance = craft;
		craft.settings = new WaylandCraftSettings();
	}
	
	private static WindowDisplay syntheticDisplay(long handle) {
		// Ensure geometry so updateGeometry does not NPE on null geometry.
		WLCToplevel toplevel = new WLCToplevel(handle);
		toplevel.geometry = new WLCAbstractWindow.SurfaceGeometry(0, 0, 64, 64);
		// settings/instance must be set by installCraft before this is called.
		return new WindowDisplay(toplevel);
	}
	
	private static DisplayHitResult syntheticHit(WindowDisplay display) {
		return new DisplayHitResult(
				display,
				null,
				Vec3.ZERO,
				Vec3.ZERO,
				Vec3.ZERO,
				null,
				1.0
		);
	}
	
	/**
	 * Records exclusive grab starts without requiring a valid framebuffer
	 * (WindowGrab.init would drop invalid displays).
	 */
	static final class TrackingCraft extends WaylandCraft {
		boolean grabStarted = false;
		WindowDisplay grabbedDisplay = null;
		
		@Override
		void startExclusiveWindowGrab(WindowDisplay target) {
			grabStarted = true;
			grabbedDisplay = target;
		}
	}
	
	private static Path projectRoot() {
		Path cwd = Path.of("").toAbsolutePath();
		if(Files.isRegularFile(cwd.resolve("gradle.properties"))) return cwd;
		Path p = cwd;
		for(int i = 0; i < 4; i++) {
			if(Files.isRegularFile(p.resolve("gradle.properties"))) return p;
			p = p.getParent();
			if(p == null) break;
		}
		return cwd;
	}
}
