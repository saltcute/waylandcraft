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
 * Drives shipped follow-hotkey target resolution: toggle only the look-at /
 * mouse-hover window; no-op when nothing is looked at (does not clear others).
 */
public class FollowHotkeyLookTargetTest {
	
	private WaylandCraft previousInstance;
	
	@BeforeEach
	void saveInstance() {
		previousInstance = WaylandCraft.instance;
	}
	
	@AfterEach
	void restoreInstance() {
		WaylandCraft.instance = previousInstance;
	}
	
	@Test
	void resolveReturnsNullWhenNoHover() {
		assertNull(WaylandCraft.resolveFollowHotkeyTarget(null));
	}
	
	@Test
	void resolveReturnsHoveredDisplayWhenPresent() {
		installCraft(new WaylandCraft());
		WindowDisplay display = syntheticDisplay(1L);
		assertSame(display, WaylandCraft.resolveFollowHotkeyTarget(syntheticHit(display)));
	}
	
	@Test
	void hoverUnlockedLocksThatDisplayOnly() {
		WaylandCraft craft = installCraft(new WaylandCraft());
		WindowDisplay lookedAt = syntheticDisplay(2L);
		WindowDisplay other = syntheticDisplay(3L);
		craft.displays.add(lookedAt);
		craft.displays.add(other);
		assertFalse(lookedAt.isFollowLocked());
		assertFalse(other.isFollowLocked());
		
		Vec3 eye = new Vec3(0, 64, 0);
		lookedAt.pivot = eye.add(1, 0, 0);
		
		WindowDisplay result = craft.applyFollowHotkey(syntheticHit(lookedAt), eye);
		
		assertSame(lookedAt, result);
		assertTrue(lookedAt.isFollowLocked());
		assertFalse(other.isFollowLocked());
	}
	
	@Test
	void hoverLockedUnlocksThatDisplayOnly() {
		WaylandCraft craft = installCraft(new WaylandCraft());
		WindowDisplay lookedAt = syntheticDisplay(4L);
		WindowDisplay otherLocked = syntheticDisplay(5L);
		craft.displays.add(lookedAt);
		craft.displays.add(otherLocked);
		
		Vec3 eye = new Vec3(10, 70, -2);
		lookedAt.pivot = eye.add(2, 0, 0);
		otherLocked.pivot = eye.add(0, 0, 3);
		lookedAt.lockFollow(eye);
		otherLocked.lockFollow(eye);
		assertTrue(lookedAt.isFollowLocked());
		assertTrue(otherLocked.isFollowLocked());
		
		WindowDisplay result = craft.applyFollowHotkey(syntheticHit(lookedAt), eye);
		
		assertSame(lookedAt, result);
		assertFalse(lookedAt.isFollowLocked());
		assertTrue(otherLocked.isFollowLocked(), "non-hovered locked window must stay locked");
	}
	
	@Test
	void noHoverDoesNotChangeAnyLocks() {
		WaylandCraft craft = installCraft(new WaylandCraft());
		WindowDisplay locked = syntheticDisplay(6L);
		WindowDisplay unlocked = syntheticDisplay(7L);
		craft.displays.add(locked);
		craft.displays.add(unlocked);
		
		Vec3 eye = new Vec3(1, 2, 3);
		locked.pivot = eye.add(0, 1, 0);
		locked.lockFollow(eye);
		assertTrue(locked.isFollowLocked());
		assertFalse(unlocked.isFollowLocked());
		
		assertNull(craft.applyFollowHotkey(null, eye));
		
		assertTrue(locked.isFollowLocked(), "no hover must not clear existing locks");
		assertFalse(unlocked.isFollowLocked(), "no hover must not lock non-hovered windows");
	}
	
	@Test
	void structuralFollowHotkeyUsesHoverAndDoesNotClearAll() throws IOException {
		Path root = projectRoot();
		String wlc = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/WaylandCraft.java"),
				StandardCharsets.UTF_8);
		
		assertTrue(wlc.contains("applyFollowHotkey") || wlc.contains("resolveFollowHotkeyTarget"),
				"follow hotkey must use a hover-resolved helper");
		assertTrue(wlc.contains("toggleFollowLock") && wlc.contains("hoveredDisplay"),
				"toggleFollowLock must use live hoveredDisplay");
		
		int start = wlc.indexOf("void toggleFollowLock");
		assertTrue(start >= 0);
		// Include applyFollowHotkey / resolve helpers that follow it.
		int end = wlc.indexOf("void applyFollowLocks", start);
		if(end < 0) end = Math.min(wlc.length(), start + 2500);
		String region = wlc.substring(start, end);
		
		assertTrue(region.contains("hoveredDisplay") || region.contains("applyFollowHotkey"),
				"follow toggle region must resolve from hover");
		assertFalse(region.contains("for(WindowDisplay display : displays)")
						&& region.contains("clearFollowLock"),
				"follow hotkey must not bulk-clear all locks on no hover");
		// No clear-all loop in the toggle / applyFollowHotkey helpers.
		assertFalse(region.contains("clear every follow")
						|| region.contains("unlock all")
						|| region.contains("clear every"),
				"docs/code must not describe clear-all on no target");
		assertTrue(region.contains("no-op") || region.contains("return null")
						|| region.contains("if(target == null)"),
				"no-hover path must no-op rather than mutate others");
	}
	
	private static WaylandCraft installCraft(WaylandCraft craft) {
		WaylandCraft.instance = craft;
		craft.settings = new WaylandCraftSettings();
		return craft;
	}
	
	private static WindowDisplay syntheticDisplay(long handle) {
		WLCToplevel toplevel = new WLCToplevel(handle);
		toplevel.geometry = new WLCAbstractWindow.SurfaceGeometry(0, 0, 64, 64);
		return new WindowDisplay(toplevel);
	}
	
	private static DisplayHitResult syntheticHit(WindowDisplay display) {
		return new DisplayHitResult(
				display, null, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, null, 1.0);
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
