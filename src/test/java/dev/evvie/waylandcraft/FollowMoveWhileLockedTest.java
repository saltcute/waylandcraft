package dev.evvie.waylandcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import dev.evvie.waylandcraft.settings.WaylandCraftSettings;
import net.minecraft.world.phys.Vec3;

/**
 * Drives shipped follow+move interaction: lock → grab-move / recapture → apply
 * uses the new world-fixed offset; translation tracks; camera turn does not orbit.
 */
public class FollowMoveWhileLockedTest {
	
	private static final double EPS = 1e-6;
	
	private WaylandCraft previousInstance;
	
	@BeforeEach
	void installInstance() {
		previousInstance = WaylandCraft.instance;
	}
	
	@AfterEach
	void restoreInstance() {
		WaylandCraft.instance = previousInstance;
	}
	
	/**
	 * Pure helpers: after reposition while locked, recapture yields new offset;
	 * apply at same player places at new pose; translate carries new delta;
	 * look change does not orbit.
	 */
	@Test
	void recaptureAfterMoveUpdatesOffsetAndPreservesWorldFixed() {
		Vec3 playerPos = new Vec3(0, 64, 0);
		// Initial NE offset
		Vec3 pivot0 = new Vec3(2, 64, 2);
		Vec3 normal0 = new Vec3(-1, 0, 0);
		Vec3 down0 = new Vec3(0, -1, 0);
		
		WindowFollowPose.RelativeLock lock0 =
				WindowFollowPose.capture(playerPos, pivot0, normal0, down0);
		
		// Player "grab-moves" window to a new world pose (e.g. NW and lower).
		Vec3 pivot1 = new Vec3(-3, 63, 1);
		Vec3 normal1 = new Vec3(0, 0, -1);
		Vec3 down1 = new Vec3(0, -1, 0);
		
		WindowFollowPose.RelativeLock lock1 =
				WindowFollowPose.recaptureAfterMove(playerPos, pivot1, normal1, down1);
		
		// Same player eye: apply must place at new pose, not old NE offset.
		WindowFollowPose.AppliedPose atSame =
				WindowFollowPose.apply(playerPos, lock1);
		assertVecEquals(pivot1, atSame.pivot(), EPS);
		assertVecEquals(normal1, atSame.normal(), EPS);
		assertVecEquals(down1, atSame.down(), EPS);
		
		// Old lock would have kept NE — ensure recapture is actually different.
		WindowFollowPose.AppliedPose oldWould =
				WindowFollowPose.apply(playerPos, lock0);
		assertVecEquals(pivot0, oldWould.pivot(), EPS);
		assertFalse(near(pivot0, pivot1, EPS), "test fixture poses must differ");
		
		// Player translation carries the new delta.
		Vec3 player2 = playerPos.add(10, 0, -4);
		WindowFollowPose.AppliedPose afterWalk =
				WindowFollowPose.apply(player2, lock1);
		assertVecEquals(player2.add(pivot1.subtract(playerPos)), afterWalk.pivot(), EPS);
		assertVecEquals(normal1, afterWalk.normal(), EPS);
		
		// Look/yaw alone still does not orbit (world-fixed).
		// capture/apply ignore look; explicit check via follow() with changed look.
		WindowFollowPose.AppliedPose afterLook = WindowFollowPose.follow(
				playerPos, new Vec3(0, 0, 1), new Vec3(0, 1, 0),
				pivot1, normal1, down1,
				playerPos, new Vec3(1, 0, 0), new Vec3(0, 1, 0)
		);
		assertVecEquals(pivot1, afterLook.pivot(), EPS);
		assertVecEquals(normal1, afterLook.normal(), EPS);
	}
	
	/**
	 * Shipped WindowDisplay path: lock → pause (as grab) → manual move →
	 * recapture → unpause → apply uses new offset; not pre-move snap-back.
	 */
	@Test
	void displayRecaptureWhileLockedKeepsNewOffsetOnApply() {
		installCraft(new WaylandCraft());
		WindowDisplay display = syntheticDisplay(11L);
		
		Vec3 playerPos = new Vec3(5, 70, 5);
		// Place window NE of player and lock.
		display.pivot = playerPos.add(2, 0, 2);
		display.rotate(new Vec3(-1, 0, 0), new Vec3(0, -1, 0));
		display.lockFollow(playerPos);
		assertTrue(display.isFollowLocked());
		
		// Simulate exclusive placement grab: pause apply so grab motion sticks.
		display.setFollowApplyPaused(true);
		assertTrue(display.isFollowApplyPaused());
		
		// Apply during grab must not snap back.
		Vec3 preMovePivot = display.pivot;
		display.pivot = playerPos.add(-4, 1, 0); // "moved" NW
		display.rotate(new Vec3(0, 0, -1), new Vec3(0, -1, 0));
		display.applyFollowIfLocked(playerPos);
		assertVecEquals(playerPos.add(-4, 1, 0), display.pivot, EPS);
		assertFalse(near(preMovePivot, display.pivot, EPS));
		
		// Re-capture new placement (as doGrabMove / finishFollowAfterGrab does).
		display.recaptureFollowLock(playerPos);
		display.setFollowApplyPaused(false);
		assertFalse(display.isFollowApplyPaused());
		assertTrue(display.isFollowLocked());
		
		// Apply at same eye: stays at new offset.
		display.applyFollowIfLocked(playerPos);
		assertVecEquals(playerPos.add(-4, 1, 0), display.pivot, EPS);
		
		// Player walks: window follows with new delta.
		Vec3 player2 = playerPos.add(3, 0, 7);
		display.applyFollowIfLocked(player2);
		assertVecEquals(player2.add(-4, 1, 0), display.pivot, EPS);
		
		// Orientation absolute (world-fixed).
		assertVecEquals(new Vec3(0, 0, -1), display.normal(), EPS);
	}
	
	/**
	 * Without pause, apply would overwrite a manual move using the old lock —
	 * documents why grab pauses. After recapture, apply respects the move.
	 */
	@Test
	void withoutRecaptureApplySnapsToOldOffset_withRecaptureKeepsMove() {
		installCraft(new WaylandCraft());
		WindowDisplay display = syntheticDisplay(12L);
		
		Vec3 player = new Vec3(0, 64, 0);
		display.pivot = player.add(1, 0, 0);
		display.rotate(new Vec3(0, 0, -1), new Vec3(0, -1, 0));
		display.lockFollow(player);
		
		// Move without recapture → apply snaps back.
		display.pivot = player.add(0, 0, 5);
		display.applyFollowIfLocked(player);
		assertVecEquals(player.add(1, 0, 0), display.pivot, EPS);
		
		// Move with recapture → apply keeps new pose.
		display.pivot = player.add(0, 0, 5);
		display.recaptureFollowLock(player);
		display.applyFollowIfLocked(player);
		assertVecEquals(player.add(0, 0, 5), display.pivot, EPS);
	}
	
	@Test
	void structuralGrabPausesFollowAndRecaptures() throws IOException {
		Path root = projectRoot();
		String grab = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/grabs/WindowGrab.java"),
				StandardCharsets.UTF_8);
		String display = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/displays/WindowDisplay.java"),
				StandardCharsets.UTF_8);
		String pose = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/WindowFollowPose.java"),
				StandardCharsets.UTF_8);
		
		assertTrue(grab.contains("setFollowApplyPaused(true)")
						|| grab.contains("setFollowApplyPaused( true"),
				"WindowGrab init must pause follow apply");
		assertTrue(grab.contains("setFollowApplyPaused(false)")
						|| grab.contains("finishFollowAfterGrab"),
				"WindowGrab release must unpause / finish follow");
		assertTrue(grab.contains("recaptureFollowLock")
						|| display.contains("recaptureFollowLock"),
				"must recapture follow lock after placement move");
		assertTrue(display.contains("followApplyPaused")
						&& display.contains("applyFollowIfLocked"),
				"WindowDisplay must gate apply on pause flag");
		assertTrue(display.contains("recaptureFollowLock"),
				"WindowDisplay must expose recaptureFollowLock");
		assertTrue(display.contains("isFollowLocked()")
						&& display.contains("recaptureFollowLock"),
				"doGrabMove path should recapture when follow-locked");
		// doGrabMove body
		int doGrab = display.indexOf("void doGrabMove");
		assertTrue(doGrab >= 0);
		String doGrabBody = display.substring(doGrab, Math.min(display.length(), doGrab + 800));
		assertTrue(doGrabBody.contains("recaptureFollowLock"),
				"doGrabMove must recapture follow lock when locked");
		assertTrue(pose.contains("recaptureAfterMove") || pose.contains("capture"),
				"WindowFollowPose must support recapture-after-move");
		// Must not clear follow on grab start (unlock-on-move is non-goal).
		assertFalse(grab.contains("clearFollowLock"),
				"WindowGrab must not clear follow lock on move");
	}
	
	private static void installCraft(WaylandCraft craft) {
		WaylandCraft.instance = craft;
		craft.settings = new WaylandCraftSettings();
	}
	
	private static WindowDisplay syntheticDisplay(long handle) {
		WLCToplevel toplevel = new WLCToplevel(handle);
		toplevel.geometry = new WLCAbstractWindow.SurfaceGeometry(0, 0, 64, 64);
		return new WindowDisplay(toplevel);
	}
	
	private static void assertVecEquals(Vec3 expected, Vec3 actual, double eps) {
		assertNotNull(actual);
		assertEquals(expected.x, actual.x, eps, "x");
		assertEquals(expected.y, actual.y, eps, "y");
		assertEquals(expected.z, actual.z, eps, "z");
	}
	
	private static boolean near(Vec3 a, Vec3 b, double eps) {
		return Math.abs(a.x - b.x) <= eps
				&& Math.abs(a.y - b.y) <= eps
				&& Math.abs(a.z - b.z) <= eps;
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
