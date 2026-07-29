package dev.evvie.waylandcraft.grabs;

import dev.evvie.waylandcraft.displays.WindowDisplay;
import dev.evvie.waylandcraft.utils.WaylandCraftUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Exclusive placement grab: moves a world window with the camera/view.
 *
 * <p>If the window is follow-locked, follow re-apply is paused for the duration
 * of the grab so motion is not overwritten, and the world-fixed follow offset
 * is re-captured after each move and on release so follow continues from the
 * new placement.
 */
public class WindowGrab extends PointerGrab {
	
	private final WindowDisplay window;
	
	public WindowGrab(WindowDisplay window, int button) {
		super(button);
		this.window = window;
		window.anchorDistance = 2.0;
	}
	
	/** The world display being placement-grabbed. */
	public WindowDisplay getWindow() {
		return window;
	}
	
	private void checkValid() throws GrabDroppedException {
		if(!window.isValid()) {
			this.drop();
		}
	}
	
	@Override
	public void init() throws GrabDroppedException {
		this.checkValid();
		// Pause follow apply so per-frame lock re-apply does not fight the grab.
		if(window.isFollowLocked()) {
			window.setFollowApplyPaused(true);
		}
	}
	
	@Override
	public void release(boolean force) throws GrabDroppedException {
		try {
			this.checkValid();
		} finally {
			// Always re-capture (if still locked) and unpause so follow resumes
			// with the post-move offset even when release drops the grab.
			finishFollowAfterGrab();
		}
	}
	
	@Override
	public void moveWorld(Vec3 pos, Vec3 view, Vec3 up, float yRot, float xRot) throws GrabDroppedException {
		this.checkValid();
		
		window.doGrabMove(pos, view, up, yRot);
		// doGrabMove already re-captures follow lock from {@code pos} when locked.
	}

	@Override
	public void onScroll(double scrollX, double scrollY) throws GrabDroppedException {
		this.checkValid();

		window.adjustAnchorDistance(scrollY);
		// Next moveWorld re-applies anchor distance; no recapture until then.
	}
	
	/**
	 * After placement grab ends: re-store world-fixed follow offset from the
	 * current pose (if still locked) and resume follow apply.
	 */
	void finishFollowAfterGrab() {
		try {
			if(window.isFollowLocked()) {
				Minecraft mc = Minecraft.getInstance();
				if(mc != null && mc.player != null) {
					window.recaptureFollowLock(WaylandCraftUtils.getPosition(mc.player));
				}
			}
		} finally {
			window.setFollowApplyPaused(false);
		}
	}

}
