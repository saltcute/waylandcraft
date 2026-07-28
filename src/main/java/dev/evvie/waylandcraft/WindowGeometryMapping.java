package dev.evvie.waylandcraft;

import dev.evvie.waylandcraft.bridge.WLCAbstractWindow.SurfaceGeometry;

/**
 * Shipped mapping between xdg window geometry, the composited surface tree, and
 * surface-local hit coordinates. Render placement and pointer pick must use the
 * same basis so CSD clients (e.g. Firefox) do not show shifted content vs clicks.
 *
 * <p>All values are in surface-local (logical) coordinates unless noted.
 */
public final class WindowGeometryMapping {
	
	private WindowGeometryMapping() {}
	
	/**
	 * Resolve effective window geometry for a surface.
	 * When the client has not set geometry, the full surface is the content rect.
	 */
	public static SurfaceGeometry effectiveGeometry(SurfaceGeometry clientGeometry, int surfaceWidth, int surfaceHeight) {
		if(clientGeometry == null) {
			return new SurfaceGeometry(0, 0, Math.max(0, surfaceWidth), Math.max(0, surfaceHeight));
		}
		return clientGeometry;
	}
	
	/**
	 * Logical X offset applied to the full surface composite so that the
	 * geometry origin (content top-left) sits at display (0, 0).
	 *
	 * @param treeXOff framebuffer tree origin offset ({@code -minX} of the surface tree)
	 * @param geometryX xdg geometry X (content origin on the root surface)
	 */
	public static int renderOffsetX(int treeXOff, int geometryX) {
		return -treeXOff - geometryX;
	}
	
	/** Logical Y offset counterpart of {@link #renderOffsetX}. */
	public static int renderOffsetY(int treeYOff, int geometryY) {
		return -treeYOff - geometryY;
	}
	
	/**
	 * Convert a point in geometry-local coordinates (0,0 = content top-left)
	 * to root surface-local coordinates (0,0 = buffer/surface top-left).
	 */
	public static double toSurfaceLocalX(double geometryLocalX, int geometryX) {
		return geometryLocalX + geometryX;
	}
	
	/** Y counterpart of {@link #toSurfaceLocalX}. */
	public static double toSurfaceLocalY(double geometryLocalY, int geometryY) {
		return geometryLocalY + geometryY;
	}
	
	/**
	 * Inverse of {@link #toSurfaceLocalX}: surface-local → geometry-local.
	 * A point on the content rect at surface (geometryX, geometryY) maps to (0, 0).
	 */
	public static double toGeometryLocalX(double surfaceLocalX, int geometryX) {
		return surfaceLocalX - geometryX;
	}
	
	public static double toGeometryLocalY(double surfaceLocalY, int geometryY) {
		return surfaceLocalY - geometryY;
	}
	
	/**
	 * Assert paint/pick agreement: the surface-local point under the geometry
	 * origin must be exactly the geometry origin on the surface.
	 */
	public static boolean paintAndPickAgree(int treeXOff, int treeYOff, SurfaceGeometry geometry) {
		if(geometry == null) return true;
		int ox = renderOffsetX(treeXOff, geometry.x());
		int oy = renderOffsetY(treeYOff, geometry.y());
		// After render offset, surface point (geometry.x, geometry.y) is at display (0,0).
		// Display (0,0) pick → surface local via toSurfaceLocal(0,0) = (geometry.x, geometry.y).
		double pickX = toSurfaceLocalX(0, geometry.x());
		double pickY = toSurfaceLocalY(0, geometry.y());
		// Display position of surface (geometry.x, geometry.y) after offset:
		double paintX = treeXOff + geometry.x() + ox; // should be 0
		double paintY = treeYOff + geometry.y() + oy;
		return paintX == 0 && paintY == 0 && pickX == geometry.x() && pickY == geometry.y();
	}
	
}
