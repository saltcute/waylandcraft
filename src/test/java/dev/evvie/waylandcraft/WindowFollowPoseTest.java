package dev.evvie.waylandcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

/**
 * Drives shipped {@link WindowFollowPose} capture/apply for player-follow lock.
 * Asserts world-fixed offset semantics: translate follows; camera turn does not orbit.
 */
public class WindowFollowPoseTest {
	
	private static final double EPS = 1e-6;
	
	@Test
	void identityApplyLeavesPoseUnchanged() {
		Vec3 playerPos = new Vec3(10, 64, -3);
		Vec3 look = new Vec3(0, 0, 1);
		Vec3 up = new Vec3(0, 1, 0);
		// Window 2 blocks in front of player, facing the player (-look).
		Vec3 pivot = playerPos.add(look.scale(2.0));
		Vec3 normal = look.scale(-1);
		Vec3 down = new Vec3(0, -1, 0);
		
		WindowFollowPose.RelativeLock lock =
				WindowFollowPose.capture(playerPos, look, up, pivot, normal, down);
		WindowFollowPose.AppliedPose applied =
				WindowFollowPose.apply(playerPos, look, up, lock);
		
		assertVecEquals(pivot, applied.pivot(), EPS);
		assertVecEquals(normal, applied.normal(), EPS);
		assertVecEquals(down, applied.down(), EPS);
	}
	
	@Test
	void translatePlayerKeepsWorldOffset() {
		Vec3 playerPos = new Vec3(0, 70, 0);
		// World offset northeast-ish: +1 X, +0.5 Y, +3 Z from player
		Vec3 pivot = new Vec3(1, 70.5, 3);
		Vec3 normal = new Vec3(0, 0, -1);
		Vec3 down = new Vec3(0, -1, 0);
		
		WindowFollowPose.RelativeLock lock =
				WindowFollowPose.capture(playerPos, pivot, normal, down);
		
		// Player walks +5 on X and -2 on Z.
		Vec3 newPos = playerPos.add(5, 0, -2);
		WindowFollowPose.AppliedPose applied = WindowFollowPose.apply(newPos, lock);
		
		Vec3 expectedPivot = newPos.add(1, 0.5, 3);
		assertVecEquals(expectedPivot, applied.pivot(), EPS);
		assertVecEquals(normal, applied.normal(), EPS);
		assertVecEquals(down, applied.down(), EPS);
	}
	
	/**
	 * Camera / look change alone must NOT orbit the window. If locked while the
	 * window is northeast of the player, that compass direction and distance stay
	 * fixed when the player only turns.
	 */
	@Test
	void cameraTurnDoesNotOrbitWindow() {
		Vec3 playerPos = new Vec3(0, 64, 0);
		Vec3 look0 = new Vec3(0, 0, 1); // +Z
		Vec3 up = new Vec3(0, 1, 0);
		// Northeast of player: +X and +Z
		Vec3 pivot0 = new Vec3(2, 64, 2);
		Vec3 normal0 = new Vec3(-1, 0, 0); // facing west (absolute)
		Vec3 down0 = new Vec3(0, -1, 0);
		
		// 90° yaw left: look becomes +X — must not move the window.
		Vec3 look1 = new Vec3(1, 0, 0);
		
		WindowFollowPose.AppliedPose applied = WindowFollowPose.follow(
				playerPos, look0, up, pivot0, normal0, down0,
				playerPos, look1, up
		);
		
		// Same world offset: still northeast of player.
		assertVecEquals(pivot0, applied.pivot(), EPS);
		// Absolute orientation unchanged.
		assertVecEquals(normal0, applied.normal(), EPS);
		assertVecEquals(down0, applied.down(), EPS);
	}
	
	@Test
	void translateMovesOffsetUnchangedByLook() {
		Vec3 p0 = new Vec3(100, 50, 100);
		Vec3 look0 = new Vec3(0, 0, 1);
		Vec3 up = new Vec3(0, 1, 0);
		// Fixed world offset (+1, 0, +4)
		Vec3 pivot0 = p0.add(1, 0, 4);
		Vec3 normal0 = new Vec3(0, 0, -1);
		Vec3 down0 = new Vec3(0, -1, 0);
		
		Vec3 p1 = new Vec3(10, 80, -20);
		Vec3 look1 = new Vec3(0, 0, -1); // 180° yaw — ignored for placement
		
		WindowFollowPose.AppliedPose applied = WindowFollowPose.follow(
				p0, look0, up, pivot0, normal0, down0,
				p1, look1, up
		);
		
		// Only translation: same world offset applied at new eye.
		assertVecEquals(p1.add(1, 0, 4), applied.pivot(), EPS);
		assertVecEquals(normal0, applied.normal(), EPS);
		assertVecEquals(down0, applied.down(), EPS);
	}
	
	@Test
	void frameWorldLocalRoundTrip() {
		Vec3 pos = new Vec3(3, 4, 5);
		Vec3 look = new Vec3(1, 0, 1);
		Vec3 up = new Vec3(0, 1, 0);
		WindowFollowPose.PlayerFrame frame = WindowFollowPose.PlayerFrame.from(pos, look, up);
		
		// Basis vectors must be orthonormal.
		assertEquals(1.0, frame.look().length(), EPS);
		assertEquals(1.0, frame.up().length(), EPS);
		assertEquals(1.0, frame.right().length(), EPS);
		assertEquals(0.0, frame.look().dot(frame.up()), EPS);
		assertEquals(0.0, frame.look().dot(frame.right()), EPS);
		assertEquals(0.0, frame.up().dot(frame.right()), EPS);
		
		Vec3 sample = new Vec3(7, -2, 11);
		Vec3 local = frame.worldToLocal(sample);
		Vec3 back = frame.localToWorld(local);
		assertVecEquals(sample, back, EPS);
	}
	
	@Test
	void livePathWiresFollowHotkeyAndApply() throws IOException {
		Path root = projectRoot();
		String wlc = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/WaylandCraft.java"),
				StandardCharsets.UTF_8);
		String display = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/displays/WindowDisplay.java"),
				StandardCharsets.UTF_8);
		String lang = Files.readString(
				root.resolve("src/main/resources/assets/waylandcraft/lang/en_us.json"),
				StandardCharsets.UTF_8);
		String followPose = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/WindowFollowPose.java"),
				StandardCharsets.UTF_8);
		
		assertTrue(wlc.contains("keyFollowWindow"),
				"must declare follow-window KeyMapping field");
		assertTrue(wlc.contains("KeyMappingHelper.registerKeyMapping")
				&& wlc.contains("waylandcraft.key.followWindow"),
				"must register follow hotkey via KeyMappingHelper like siblings");
		assertTrue(wlc.contains("consumeClick()") && wlc.contains("keyFollowWindow"),
				"must consume follow hotkey on client tick path");
		assertTrue(wlc.contains("WindowFollowPose.capture")
				|| wlc.contains("WindowFollowPose.apply")
				|| display.contains("WindowFollowPose"),
				"live path must call WindowFollowPose");
		assertTrue(display.contains("applyFollowIfLocked") || wlc.contains("applyFollow"),
				"must apply follow pose while locked");
		assertTrue(lang.contains("waylandcraft.key.followWindow"),
				"lang must name the follow hotkey");
		// Default must not collide with B/V/G siblings.
		int followReg = wlc.indexOf("waylandcraft.key.followWindow");
		assertTrue(followReg >= 0);
		String regSlice = wlc.substring(followReg, Math.min(wlc.length(), followReg + 200));
		assertFalse(regSlice.contains("GLFW_KEY_B"), "default must not be B (grab)");
		assertFalse(regSlice.contains("GLFW_KEY_V"), "default must not be V (app launcher)");
		assertFalse(regSlice.contains("GLFW_KEY_G"), "default must not be G (capture keyboard)");
		
		// World-fixed semantics in shipped helper.
		assertTrue(followPose.contains("world-fixed") || followPose.contains("world-space")
						|| followPose.contains("World-fixed") || followPose.contains("world axes"),
				"WindowFollowPose docs/logic must describe world-fixed offset");
	}
	
	@Test
	void livePathWiresGrabHotkeyDefaultB() throws IOException {
		Path root = projectRoot();
		String wlc = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/WaylandCraft.java"),
				StandardCharsets.UTF_8);
		String wm = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/gui/WindowManagerScreen.java"),
				StandardCharsets.UTF_8);
		String lang = Files.readString(
				root.resolve("src/main/resources/assets/waylandcraft/lang/en_us.json"),
				StandardCharsets.UTF_8);
		
		assertTrue(wlc.contains("keyGrabWindow"),
				"must declare grab KeyMapping field");
		assertTrue(wlc.contains("waylandcraft.key.grabWindow"),
				"must register grab hotkey with lang key");
		int grabReg = wlc.indexOf("waylandcraft.key.grabWindow");
		assertTrue(grabReg >= 0);
		String grabSlice = wlc.substring(grabReg, Math.min(wlc.length(), grabReg + 200));
		assertTrue(grabSlice.contains("GLFW_KEY_B"),
				"grab hotkey default must be B");
		assertTrue(wlc.contains("keyGrabWindow") && wlc.contains("consumeClick()")
						&& wlc.contains("startWindowGrab"),
				"must consume grab hotkey and call startWindowGrab");
		assertTrue(wlc.contains("new WindowGrab") && wlc.contains("startExclusive"),
				"grab hotkey path must start exclusive WindowGrab like WM button");
		// Look-at / hover target (not most-recent focus).
		assertTrue(wlc.contains("hoveredDisplay") && wlc.contains("resolveGrabHotkeyTarget"),
				"grab hotkey must resolve from hoveredDisplay (mouse look-at standard)");
		int startGrab = wlc.indexOf("void startWindowGrab");
		assertTrue(startGrab >= 0);
		int afterStart = wlc.indexOf("void toggleFollowLock", startGrab);
		if(afterStart < 0) afterStart = Math.min(wlc.length(), startGrab + 1500);
		String startGrabRegion = wlc.substring(startGrab, afterStart);
		assertFalse(startGrabRegion.contains("getMostRecentFocus"),
				"grab hotkey must not fall back to most-recent focus");
		assertTrue(wm.contains("onGrabPressed") && wm.contains("new WindowGrab")
						&& wm.contains("startExclusive"),
				"WM Grab button must still start exclusive WindowGrab");
		assertTrue(lang.contains("waylandcraft.key.grabWindow"),
				"lang must name the grab hotkey");
		// Window manager must not still default to B.
		int wmReg = wlc.indexOf("waylandcraft.key.windowManager");
		assertTrue(wmReg >= 0);
		String wmSlice = wlc.substring(wmReg, Math.min(wlc.length(), wmReg + 200));
		assertFalse(wmSlice.contains("GLFW_KEY_B"),
				"window manager default must be reassigned off B");
	}
	
	@Test
	void livePathAppliesFollowPerFrameWithInterpolatedPosition() throws IOException {
		Path root = projectRoot();
		String wlc = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/WaylandCraft.java"),
				StandardCharsets.UTF_8);
		String utils = Files.readString(
				root.resolve("src/main/java/dev/evvie/waylandcraft/utils/WaylandCraftUtils.java"),
				StandardCharsets.UTF_8);
		
		// Follow apply must run from a per-frame path (renderWorld and/or updateWorld),
		// not only END_CLIENT_TICK.
		int renderWorld = wlc.indexOf("void renderWorld");
		int updateWorld = wlc.indexOf("void updateWorld");
		int onClientTick = wlc.indexOf("void onClientTick");
		assertTrue(renderWorld >= 0, "renderWorld must exist");
		assertTrue(updateWorld >= 0, "updateWorld must exist");
		assertTrue(onClientTick > updateWorld, "onClientTick should follow updateWorld in source");
		
		String renderBody = wlc.substring(renderWorld, updateWorld);
		String updateWorldBody = wlc.substring(updateWorld, onClientTick);
		assertTrue(renderBody.contains("applyFollowLocks") || updateWorldBody.contains("applyFollowLocks"),
				"applyFollowLocks must be invoked from renderWorld or updateWorld (per-frame path)");
		// Prefer pre-submit apply so the drawn frame uses the current pose.
		assertTrue(renderBody.contains("applyFollowLocks"),
				"applyFollowLocks should run in renderWorld before display submit");
		
		// onClientTick should not be the sole (or primary) apply site anymore.
		int onClientTickEnd = wlc.indexOf("void checkKeybinds", onClientTick);
		if(onClientTickEnd < 0) onClientTickEnd = Math.min(wlc.length(), onClientTick + 400);
		String tickBody = wlc.substring(onClientTick, onClientTickEnd);
		assertFalse(tickBody.contains("applyFollowLocks"),
				"applyFollowLocks should not run only on client tick; moved to per-frame");
		
		// Interpolated position via partial ticks.
		assertTrue(utils.contains("getGameTimeDeltaPartialTick")
						&& utils.contains("Mth.lerp"),
				"getPosition must interpolate with partial ticks");
		assertTrue(wlc.contains("WaylandCraftUtils.getPosition"),
				"follow apply must sample player via WaylandCraftUtils.getPosition");
	}
	
	private static void assertVecEquals(Vec3 expected, Vec3 actual, double eps) {
		assertEquals(expected.x, actual.x, eps, "x");
		assertEquals(expected.y, actual.y, eps, "y");
		assertEquals(expected.z, actual.z, eps, "z");
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
