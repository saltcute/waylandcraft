package dev.evvie.waylandcraft;

/**
 * Pure helpers for integer Wayland output / buffer scale.
 * Used by settings validation, coordinate mapping, and unit tests.
 */
public final class IntegerScale {
	
	public static final int DEFAULT = 1;
	public static final int MIN = 1;
	/** Soft upper bound to avoid absurd values from settings UI. */
	public static final int MAX = 8;
	
	private IntegerScale() {}
	
	/**
	 * Clamp a requested integer scale to a safe value ≥ {@link #MIN}.
	 * Non-positive and out-of-range values are clamped, not rejected with null.
	 */
	public static int clamp(int scale) {
		if(scale < MIN) return MIN;
		if(scale > MAX) return MAX;
		return scale;
	}
	
	/** True if {@code scale} is already a valid stored scale (no clamping needed). */
	public static boolean isValid(int scale) {
		return scale >= MIN && scale <= MAX;
	}
	
	/**
	 * Convert buffer-pixel size to logical surface size for the given buffer scale.
	 * Scale 1 is identity; scale 2 halves dimensions (integer division).
	 */
	public static int bufferToLogical(int bufferPixels, int bufferScale) {
		int scale = clamp(bufferScale);
		return Math.max(1, bufferPixels / scale);
	}
	
	/**
	 * Convert logical surface coordinate to buffer-pixel coordinate.
	 * Scale 1 is identity; scale 2 multiplies by 2.
	 */
	public static double logicalToBuffer(double logical, int bufferScale) {
		return logical * clamp(bufferScale);
	}
	
	/**
	 * Convert buffer-pixel coordinate to logical surface coordinate.
	 * Scale 1 is identity; scale 2 divides by 2.
	 */
	public static double bufferToLogicalCoord(double bufferCoord, int bufferScale) {
		return bufferCoord / (double) clamp(bufferScale);
	}
	
	/**
	 * Pixel size of the in-game window composite for a logical size and integer scale.
	 * Scale 1 → same as logical; scale 2 → 2× logical pixels (preserves HiDPI buffers).
	 * World footprint must continue to use logical size separately.
	 */
	public static int compositePixels(int logicalSize, int scale) {
		if(logicalSize <= 0) return 0;
		return Math.max(1, logicalSize * clamp(scale));
	}
	
	/**
	 * Resolve the integer factor used to size the window composite GPU targets.
	 * Uses only the maximum client {@code buffer_scale} actually present on
	 * surfaces so the target matches submitted buffers. Output-scale setting
	 * alone must not inflate the composite (that left empty padding when
	 * clients still submit scale-1 buffers).
	 */
	public static int resolveCompositeScale(int maxBufferScale) {
		return clamp(maxBufferScale);
	}
	
	/**
	 * @deprecated Use {@link #resolveCompositeScale(int)}; outputScale must not
	 *             size the GPU composite without matching buffer_scale.
	 */
	@Deprecated
	public static int resolveCompositeScale(int maxBufferScale, int outputScale) {
		return resolveCompositeScale(maxBufferScale);
	}
	
}
