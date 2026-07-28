package dev.evvie.waylandcraft;

/**
 * Shipped placement of a Wayland surface into the high-res window composite.
 *
 * <p>Composite targets are sized {@code logical × compositeScale}. Draw quads must
 * use the same composite scale so content fills the target (not a quarter/corner
 * when scale ≥ 2). Viewport UVs map logical crop rects into buffer pixels using
 * the surface's own {@code buffer_scale}.
 */
public final class SurfaceBakePlacement {
	
	public record BakeQuad(
			float x, float y, float w, float h,
			float u1, float v1, float u2, float v2
	) {}
	
	private SurfaceBakePlacement() {}
	
	/**
	 * Place a surface-sized logical rect into composite pixel space and compute UVs.
	 *
	 * @param logicalX      logical X in the composite tree (including tree offset)
	 * @param logicalY      logical Y in the composite tree
	 * @param logicalW      logical surface width
	 * @param logicalH      logical surface height
	 * @param compositeScale scale used to size the GPU composite (must match target)
	 * @param bufferW       attached buffer pixel width
	 * @param bufferH       attached buffer pixel height
	 * @param bufferScale   client {@code wl_surface} buffer_scale for this surface
	 * @param hasViewport   whether a viewport source crop is set
	 * @param srcX          viewport source X (logical / surface-local)
	 * @param srcY          viewport source Y
	 * @param srcW          viewport source width
	 * @param srcH          viewport source height
	 */
	public static BakeQuad place(
			double logicalX, double logicalY,
			int logicalW, int logicalH,
			int compositeScale,
			int bufferW, int bufferH,
			int bufferScale,
			boolean hasViewport,
			double srcX, double srcY, double srcW, double srcH
	) {
		int cs = IntegerScale.clamp(compositeScale);
		int bs = IntegerScale.clamp(bufferScale);
		
		// Placement must use compositeScale so a scale-2 target is fully covered
		// by a full-size logical surface (quarter-fill if this used scale 1).
		float x = (float) IntegerScale.logicalToBuffer(logicalX, cs);
		float y = (float) IntegerScale.logicalToBuffer(logicalY, cs);
		float w = (float) IntegerScale.logicalToBuffer(Math.max(0, logicalW), cs);
		float h = (float) IntegerScale.logicalToBuffer(Math.max(0, logicalH), cs);
		
		float u1 = 0.0f;
		float v1 = 0.0f;
		float u2 = 1.0f;
		float v2 = 1.0f;
		
		if(hasViewport && bufferW > 0 && bufferH > 0) {
			// Viewport src is surface-local (logical). Map to buffer UVs via buffer_scale only
			// — do not use compositeScale here (would double-scale when they match).
			u1 = (float) (IntegerScale.logicalToBuffer(srcX, bs) / (double) bufferW);
			v1 = (float) (IntegerScale.logicalToBuffer(srcY, bs) / (double) bufferH);
			u2 = (float) (IntegerScale.logicalToBuffer(srcX + srcW, bs) / (double) bufferW);
			v2 = (float) (IntegerScale.logicalToBuffer(srcY + srcH, bs) / (double) bufferH);
		}
		
		return new BakeQuad(x, y, w, h, u1, v1, u2, v2);
	}
	
	/**
	 * Area of the bake quad relative to the composite target for a full-window surface
	 * at the origin. Scale 1 and scale 2 both return 1.0 when placement matches composite sizing.
	 */
	public static double fullSurfaceFillRatio(int logicalW, int logicalH, int scale) {
		if(logicalW <= 0 || logicalH <= 0) return 0;
		int s = IntegerScale.clamp(scale);
		int[] comp = new int[] {
				IntegerScale.compositePixels(logicalW, s),
				IntegerScale.compositePixels(logicalH, s)
		};
		int bufW = IntegerScale.compositePixels(logicalW, s);
		int bufH = IntegerScale.compositePixels(logicalH, s);
		BakeQuad q = place(0, 0, logicalW, logicalH, s, bufW, bufH, s, false, 0, 0, 0, 0);
		double area = (double) q.w() * (double) q.h();
		double target = (double) comp[0] * (double) comp[1];
		return target <= 0 ? 0 : area / target;
	}
	
}
