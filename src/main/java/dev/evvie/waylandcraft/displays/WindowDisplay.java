package dev.evvie.waylandcraft.displays;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.evvie.waylandcraft.WaylandCraft;
import dev.evvie.waylandcraft.WindowFollowPose;
import dev.evvie.waylandcraft.WindowGeometryMapping;
import dev.evvie.waylandcraft.bridge.WLCAbstractWindow;
import dev.evvie.waylandcraft.bridge.WLCSurface;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.math.WorldPlane;
import dev.evvie.waylandcraft.render.RenderUtils;
import dev.evvie.waylandcraft.utils.WaylandCraftUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class WindowDisplay extends AbstractWindowDisplay {
	
	public final WLCAbstractWindow window;
	public double anchorDistance = 2.0;
	
	/** Non-null while this window is locked to follow the player. */
	private @Nullable WindowFollowPose.RelativeLock followLock = null;
	
	/**
	 * When true, {@link #applyFollowIfLocked} is a no-op so exclusive placement
	 * grab can move the window without the follow lock snapping it back.
	 * Follow stays locked; re-capture updates the offset after the move.
	 */
	private boolean followApplyPaused = false;
	
	public WindowDisplay(WLCAbstractWindow window) {
		this.window = window;
		this.updateGeometry();
	}
	
	/** Whether this world window is currently locked to follow the player. */
	public boolean isFollowLocked() {
		return followLock != null;
	}
	
	public @Nullable WindowFollowPose.RelativeLock getFollowLock() {
		return followLock;
	}
	
	/** Store a player-relative lock (or clear with null). */
	public void setFollowLock(@Nullable WindowFollowPose.RelativeLock lock) {
		this.followLock = lock;
	}
	
	public void clearFollowLock() {
		this.followLock = null;
		this.followApplyPaused = false;
	}
	
	/**
	 * Pause or resume follow re-apply (used while exclusive placement grab is
	 * moving this window). Does not clear the lock.
	 */
	public void setFollowApplyPaused(boolean paused) {
		this.followApplyPaused = paused;
	}
	
	public boolean isFollowApplyPaused() {
		return followApplyPaused;
	}
	
	/**
	 * Capture current pivot/orientation as a world-fixed offset from the player
	 * eye and enter follow-lock. Look is ignored — orientation stays absolute.
	 */
	public void lockFollow(Vec3 playerPos, Vec3 playerLook, Vec3 playerUp) {
		this.followLock = WindowFollowPose.capture(playerPos, pivot, normal(), down());
	}
	
	/** Capture world-fixed offset from player eye and enter follow-lock. */
	public void lockFollow(Vec3 playerPos) {
		this.followLock = WindowFollowPose.capture(playerPos, pivot, normal(), down());
	}
	
	/**
	 * While follow-locked, re-store the world-fixed offset from the current
	 * pivot/orientation (e.g. after the player grab-moved the window). Follow
	 * stays on; subsequent apply uses the new offset.
	 */
	public void recaptureFollowLock(Vec3 playerPos) {
		if(followLock == null) return;
		this.followLock = WindowFollowPose.capture(playerPos, pivot, normal(), down());
	}
	
	/**
	 * If follow-locked, re-apply the world-fixed offset so the window tracks
	 * player location without rotating with the camera.
	 */
	public void applyFollowIfLocked(Vec3 playerPos, Vec3 playerLook, Vec3 playerUp) {
		applyFollowIfLocked(playerPos);
	}
	
	/**
	 * If follow-locked and not paused for placement grab, re-apply world-fixed
	 * offset from the player eye.
	 */
	public void applyFollowIfLocked(Vec3 playerPos) {
		if(followLock == null || followApplyPaused) return;
		WindowFollowPose.AppliedPose pose = WindowFollowPose.apply(playerPos, followLock);
		this.pivot = pose.pivot();
		this.rotate(pose.normal(), pose.down());
	}
	
	@Override
	public boolean isValid() {
		return window.isAlive() && window.framebuffer != null && window.framebuffer.isValid();
	}
	
	@Override
	public void updateGeometry() {
		setPixelScale(1.0f / WaylandCraft.instance.settings.getPixelsPerBlock());
		// Outer size is content geometry only — no SSD outer-frame expansion.
		width = window.geometry.width();
		height = window.geometry.height();
		geometryX = window.geometry.x();
		geometryY = window.geometry.y();
	}
	
	@Override
	public void renderFramebuffer(PoseStack poseStack, SubmitNodeCollector collector, Vec3 origin, Vec3 spanX, Vec3 spanY) {
		RenderUtils.renderFramebuffer(window.framebuffer, poseStack, collector, true, origin, spanX, spanY);
	}
	
	@Override
	public @Nullable FramebufferRenderable getFramebuffer() {
		return window.framebuffer;
	}
	
	/* Perform ray-window plane intersection
	 * `dir` must be normalized.
	 */
	public @Nullable DisplayHitResult intersect(Vec3 pos, Vec3 dir) {
		WorldPlane.Intersection intersection = getPlane().intersect(pos, dir);
		if(intersection == null) return null;
		
		Vec3 hitPos = intersection.world();
		// Content-local coordinates (outer size == content geometry; no SSD chrome).
		Vec3 geometryLocal = intersection.local();
		
		// Same CSD mapping as AbstractWindowDisplay render placement (WindowGeometryMapping).
		Vec3 localCoords = new Vec3(
				WindowGeometryMapping.toSurfaceLocalX(geometryLocal.x, window.geometry.x()),
				WindowGeometryMapping.toSurfaceLocalY(geometryLocal.y, window.geometry.y()),
				geometryLocal.z
		);
		
		double dist = intersection.dist();
		
		WLCSurface hitSurface = null;
		Vec3 localCoordsRelative = null;
		
		for(WLCSurface surface = window.getSurfaceTreeLast(); surface != null; surface = surface.getPrevChild()) {
			Vec3 rel = localCoords.subtract(surface.xSubpos, surface.ySubpos, 0);
			
			int width = surface.width();
			int height = surface.height();
			
			if(rel.x < 0 || rel.y < 0 || rel.x > width || rel.y > height) {
				continue;
			}
			
			if(WaylandCraft.instance.bridge.inputRegionContains(surface, rel.x, rel.y)) {
				hitSurface = surface;
				localCoordsRelative = rel;
				break;
			}
		}
		
		return new DisplayHitResult(this, hitSurface, hitPos, geometryLocal, localCoords, localCoordsRelative, dist);
	}

	public void adjustAnchorDistance(double delta) {
		this.anchorDistance = Math.clamp(this.anchorDistance + delta * 0.1d, 0.5d, 20d);
	}
	
	public void anchorToPosView(Vec3 pos, Vec3 look, Vec3 up) {
		this.pivot = pos.add(look.scale(this.anchorDistance));
		this.rotate(look.reverse(), up.reverse());
	}
	
	public void anchorToCamera(Camera camera) {
		anchorToPosView(camera.position(), new Vec3(camera.forwardVector()), new Vec3(camera.upVector()));
	}
	
	public void anchorToEntity(Entity entity) {
		anchorToPosView(WaylandCraftUtils.getPosition(entity), WaylandCraftUtils.getLookVector(entity), WaylandCraftUtils.getUpVector(entity));
	}
	
	public void doGrabMove(Vec3 pos, Vec3 view, Vec3 up, float yRot) {
		this.anchorToPosView(pos, view, up);
		
		boolean modDown = InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_LEFT_ALT);
		boolean ctrlDown = InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL);
		if(modDown) {
			this.tryAttachWalls(pos, view, yRot, ctrlDown);
		}
		else if(ctrlDown) {
			this.trySnapToOtherWindows(pos, view);
		}
		
		// Keep follow lock in sync with the new placement so release / next
		// apply uses the moved offset (not the pre-grab one).
		if(isFollowLocked()) {
			recaptureFollowLock(pos);
		}
	}
	
	public void tryAttachWalls(Vec3 pos, Vec3 view, float yRot, boolean snap) {
		BlockHitResult hitResult = Minecraft.getInstance().level.clip(new ClipContext(pos, pos.add(view.scale(32.0)), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, Minecraft.getInstance().player));
		if(hitResult.getType() != HitResult.Type.BLOCK) return;
		
		Direction blockNormal = hitResult.getDirection();
		Direction viewDirection = Direction.fromYRot(yRot);
		
		this.pivot = hitResult.getLocation().add(blockNormal.getUnitVec3().scale(0.03));
		
		Vec3 normal = blockNormal.getUnitVec3();
		Vec3 down;
		
		if(snap) {
			double centerX = (double) Math.round(pivot.x * 2) / 2;
			double centerY = (double) Math.round(pivot.y * 2) / 2;
			double centerZ = (double) Math.round(pivot.z * 2) / 2;
			
			if(blockNormal.getAxis().equals(Axis.X)) {
				this.pivot = new Vec3(pivot.x, centerY, centerZ);
			}
			else if(blockNormal.getAxis().equals(Axis.Y)) {
				this.pivot = new Vec3(centerX, pivot.y, centerZ);
			}
			else if(blockNormal.getAxis().equals(Axis.Z)) {
				this.pivot = new Vec3(centerX, centerY, pivot.z);
			}
			
			Direction downDirection = Direction.DOWN;
			if(blockNormal.equals(Direction.UP)) {
				downDirection = viewDirection.getOpposite();
			}
			else if(blockNormal.equals(Direction.DOWN)) {
				downDirection = viewDirection;
			}
			down = downDirection.getUnitVec3();
		}
		else {
			if(blockNormal.getAxis() == Axis.Y) {
				down = new Vec3(-Mth.sin(yRot * Mth.DEG_TO_RAD), 0, Mth.cos(yRot * Mth.DEG_TO_RAD));
				down = down.scale(-blockNormal.getStepY());
			}
			else {
				down = new Vec3(0, -1, 0);
			}
		}
		
		this.rotate(normal, down);
	}
	
	public void trySnapToOtherWindows(Vec3 pos, Vec3 view) {
		for(WindowDisplay display : WaylandCraft.instance.displays) {
			if(display == this) continue;
			if(!(display.window instanceof WLCToplevel)) continue;
			
			DisplayHitResult result = display.intersect(pos, view);
			if(result == null) continue;
			
			double gx = result.geometryLocal.x();
			double gy = result.geometryLocal.y();
			
			double w = display.width;
			double h = display.height;
			
			double cx = gx - w / 2;
			double cy = gy - h / 2;
			
			final double snapDistInner = Math.min(w / 2, h / 2) * 0.75;
			final double snapDistOuter = 300;
			final double snapDistInnerCorner = 100;
			final double margin = 30;
			
			double dx = Math.abs(cx) - w / 2;
			double dy = Math.abs(cy) - h / 2;
			
			boolean snapXCorner = dx > -snapDistInnerCorner && dx < snapDistOuter;
			boolean snapYCorner = dy > -snapDistInnerCorner && dy < snapDistOuter;
			
			// Corner snapping
			if(snapXCorner && snapYCorner) {
				rotate(display.normal(), display.down());
				Vec3 wx = display.localX().scale(cx < 0 ? -width - margin : w + margin);
				Vec3 wy = display.localY().scale(cy < 0 ? -height - margin : h + margin);
				moveOrigin(display.origin().add(wx).add(wy));
				return;
			}
			
			boolean snapX = dx < snapDistOuter && dx > -snapDistInner;
			boolean snapY = dy < snapDistOuter && dy > -snapDistInner;
			
			// Top / bottom edge snapping
			if(snapY && gx >= 0 && gx <= w) {
				rotate(display.normal(), display.down());
				pivot = display.pivot.add(display.localY().scale(Math.signum(cy) * (height / 2 + h / 2 + margin)));
				return;
			}
			
			// Left / right edge snapping
			if(snapX && gy >= 0 && gy <= h) {
				rotate(display.normal(), display.down());
				pivot = display.pivot.add(display.localX().scale(Math.signum(cx) * (width / 2 + w / 2 + margin)));
				return;
			}
		}
	}
	
	public static class DisplayHitResult {
		
		// WindowDisplay that was raycasted
		public final WindowDisplay target;
		
		// Surface that was hit, if any
		public final @Nullable WLCSurface surface;
		
		// World position
		public final Vec3 position;
		
		// Coordinates relative to window geometry origin
		public final Vec3 geometryLocal;
		
		// Root surface surface-local coordinates
		public final Vec3 surfaceLocalOrigin;
		
		// Surface-local coordinates relative to hit surface. Always guaranteed to not be null, if `surface` is non-null.
		public final @Nullable Vec3 surfaceLocalRelative;
		
		// Calculated distance
		public final double dist;
		
		public DisplayHitResult(WindowDisplay target, WLCSurface surface, Vec3 position, Vec3 geometryLocal, Vec3 surfaceLocalOrigin, Vec3 surfaceLocalRelative, double dist) {
			this.target = target;
			this.surface = surface;
			this.position = position;
			this.geometryLocal = geometryLocal;
			this.surfaceLocalOrigin = surfaceLocalOrigin;
			this.surfaceLocalRelative = surfaceLocalRelative;
			this.dist = dist;
		}
		
		public boolean isMiss() {
			return surface == null;
		}
		
		@Override
		public String toString() {
			return "{target=" + target + ", surface=" + surface + ", position=" + position + ", local=" + surfaceLocalOrigin + ", relative=" + surfaceLocalRelative + ", dist=" + dist + "}";
		}
		
	}
	
}
