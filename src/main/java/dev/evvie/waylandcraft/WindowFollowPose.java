package dev.evvie.waylandcraft;

import net.minecraft.world.phys.Vec3;

/**
 * Pure world-fixed window pose lock/apply for player-follow.
 *
 * <p>At lock time, the window pivot is stored as a fixed world-space offset from
 * the player eye position, and orientation (normal + down) is stored as absolute
 * world directions. While locked, {@link #apply} only translates with the player:
 * look/yaw/pitch changes do not orbit or re-aim the window.
 *
 * <p>Free of Minecraft client lifecycle so unit tests can call capture/apply
 * with synthetic {@link Vec3} fixtures.
 */
public final class WindowFollowPose {
	
	private static final double EPSILON = 1e-8;
	
	private WindowFollowPose() {}
	
	/**
	 * World-fixed lock: offset from player eye in world axes, plus absolute
	 * window orientation.
	 */
	public record RelativeLock(
			double offsetX, double offsetY, double offsetZ,
			double normalX, double normalY, double normalZ,
			double downX, double downY, double downZ
	) {}
	
	/** World pose after applying a lock to a player eye position. */
	public record AppliedPose(Vec3 pivot, Vec3 normal, Vec3 down) {}
	
	/**
	 * Orthonormal player frame (utility for tests / callers that still need a
	 * look-relative basis). Not used by follow capture/apply.
	 */
	public record PlayerFrame(Vec3 position, Vec3 look, Vec3 up, Vec3 right) {
		
		public static PlayerFrame from(Vec3 position, Vec3 look, Vec3 up) {
			Vec3 lookN = normalizeSafe(look, new Vec3(0, 0, 1));
			Vec3 right = lookN.cross(up);
			if(right.lengthSqr() < EPSILON * EPSILON) {
				// Look nearly parallel to up — pick a stable perpendicular.
				right = lookN.cross(new Vec3(0, 1, 0));
				if(right.lengthSqr() < EPSILON * EPSILON) {
					right = lookN.cross(new Vec3(1, 0, 0));
				}
			}
			right = normalizeSafe(right, new Vec3(1, 0, 0));
			// Re-orthogonalize up so (right, up, look) is right-handed orthonormal.
			Vec3 upN = normalizeSafe(right.cross(lookN), new Vec3(0, 1, 0));
			return new PlayerFrame(position, lookN, upN, right);
		}
		
		public Vec3 worldToLocal(Vec3 worldVec) {
			return new Vec3(worldVec.dot(right), worldVec.dot(up), worldVec.dot(look));
		}
		
		public Vec3 localToWorld(Vec3 local) {
			return right.scale(local.x).add(up.scale(local.y)).add(look.scale(local.z));
		}
	}
	
	/**
	 * Capture the window's pose as a world-space offset from the player and
	 * absolute orientation. Look vectors are accepted for API continuity with
	 * call sites but are ignored — placement is not camera-relative.
	 *
	 * @param playerPos player eye position
	 * @param playerLook ignored (kept for call-site compatibility)
	 * @param playerUp ignored (kept for call-site compatibility)
	 * @param pivot window world pivot
	 * @param normal window facing normal
	 * @param down window down orientation
	 */
	public static RelativeLock capture(
			Vec3 playerPos, Vec3 playerLook, Vec3 playerUp,
			Vec3 pivot, Vec3 normal, Vec3 down
	) {
		return capture(playerPos, pivot, normal, down);
	}
	
	/** Capture world-fixed offset and absolute orientation. */
	public static RelativeLock capture(Vec3 playerPos, Vec3 pivot, Vec3 normal, Vec3 down) {
		Vec3 offset = pivot.subtract(playerPos);
		Vec3 normalN = normalizeSafe(normal, new Vec3(0, 0, 1));
		Vec3 downN = reorthogonalizeDown(normalN, down);
		return new RelativeLock(
				offset.x, offset.y, offset.z,
				normalN.x, normalN.y, normalN.z,
				downN.x, downN.y, downN.z
		);
	}
	
	/**
	 * Apply a previously captured world-fixed lock to a new player eye position.
	 * Look vectors are accepted for API continuity but ignored.
	 */
	public static AppliedPose apply(
			Vec3 playerPos, Vec3 playerLook, Vec3 playerUp,
			RelativeLock lock
	) {
		return apply(playerPos, lock);
	}
	
	/** Apply world-fixed offset + absolute orientation to a player eye position. */
	public static AppliedPose apply(Vec3 playerPos, RelativeLock lock) {
		Vec3 pivot = playerPos.add(lock.offsetX(), lock.offsetY(), lock.offsetZ());
		Vec3 normal = normalizeSafe(
				new Vec3(lock.normalX(), lock.normalY(), lock.normalZ()),
				new Vec3(0, 0, 1)
		);
		Vec3 down = normalizeSafe(
				new Vec3(lock.downX(), lock.downY(), lock.downZ()),
				new Vec3(0, -1, 0)
		);
		down = reorthogonalizeDown(normal, down);
		return new AppliedPose(pivot, normal, down);
	}
	
	/**
	 * Convenience: capture then apply under a new player position in one call
	 * (used by tests and as the logical follow step). Look is accepted for
	 * API continuity but does not affect placement.
	 */
	public static AppliedPose follow(
			Vec3 lockPlayerPos, Vec3 lockLook, Vec3 lockUp,
			Vec3 windowPivot, Vec3 windowNormal, Vec3 windowDown,
			Vec3 newPlayerPos, Vec3 newLook, Vec3 newUp
	) {
		RelativeLock lock = capture(lockPlayerPos, windowPivot, windowNormal, windowDown);
		return apply(newPlayerPos, lock);
	}
	
	/** Convenience capture/apply using only eye positions (preferred). */
	public static AppliedPose follow(
			Vec3 lockPlayerPos,
			Vec3 windowPivot, Vec3 windowNormal, Vec3 windowDown,
			Vec3 newPlayerPos
	) {
		RelativeLock lock = capture(lockPlayerPos, windowPivot, windowNormal, windowDown);
		return apply(newPlayerPos, lock);
	}
	
	/**
	 * After the player repositions a follow-locked window, re-capture the
	 * world-fixed lock from the new pose at the current player eye. Subsequent
	 * {@link #apply} uses this new offset (follow stays active).
	 */
	public static RelativeLock recaptureAfterMove(
			Vec3 playerPos, Vec3 newPivot, Vec3 newNormal, Vec3 newDown
	) {
		return capture(playerPos, newPivot, newNormal, newDown);
	}
	
	static Vec3 normalizeSafe(Vec3 v, Vec3 fallback) {
		double len = v.length();
		if(len < EPSILON) return fallback;
		return v.scale(1.0 / len);
	}
	
	/**
	 * Project {@code down} onto the plane orthogonal to {@code normal} and
	 * re-normalize so the display right = normal × down stays well-defined.
	 */
	static Vec3 reorthogonalizeDown(Vec3 normal, Vec3 down) {
		Vec3 projected = down.subtract(normal.scale(down.dot(normal)));
		if(projected.lengthSqr() < EPSILON * EPSILON) {
			// down parallel to normal — pick a stable perpendicular fallback.
			Vec3 candidate = new Vec3(0, -1, 0);
			projected = candidate.subtract(normal.scale(candidate.dot(normal)));
			if(projected.lengthSqr() < EPSILON * EPSILON) {
				candidate = new Vec3(0, 0, -1);
				projected = candidate.subtract(normal.scale(candidate.dot(normal)));
			}
		}
		return normalizeSafe(projected, new Vec3(0, -1, 0));
	}
}
