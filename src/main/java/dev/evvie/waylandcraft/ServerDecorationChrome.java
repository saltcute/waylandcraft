package dev.evvie.waylandcraft;

/**
 * Pure layout for compositor-drawn server-side decoration (SSD) chrome.
 * Used by the live display paint path and pointer pick so content and chrome
 * share one inset basis.
 *
 * <p>Outer frame = content size expanded by {@link #insets()}. Content sits at
 * ({@link #contentOffsetX()}, {@link #contentOffsetY()}) within the outer rect.
 */
public final class ServerDecorationChrome {
	
	/** Titlebar height in logical surface pixels. */
	public static final int TITLEBAR_HEIGHT = 28;
	
	/** Side / bottom border width in logical surface pixels. */
	public static final int BORDER = 4;
	
	/** Titlebar fill color (ARGB). */
	public static final int TITLEBAR_COLOR = 0xFF2B2B2B;
	
	/** Border / frame fill color (ARGB). */
	public static final int BORDER_COLOR = 0xFF1A1A1A;
	
	/** Accent strip under titlebar (ARGB). */
	public static final int ACCENT_COLOR = 0xFF4A90D9;
	
	public record Insets(int left, int top, int right, int bottom) {
		public int horizontal() {
			return left + right;
		}
		
		public int vertical() {
			return top + bottom;
		}
	}
	
	private ServerDecorationChrome() {}
	
	/** Chrome insets around client content. */
	public static Insets insets() {
		return new Insets(BORDER, TITLEBAR_HEIGHT, BORDER, BORDER);
	}
	
	/** Whether policy currently draws SSD chrome. */
	public static boolean isActive() {
		return DecorationsPolicy.supportsServerSide();
	}
	
	public static int contentOffsetX() {
		return insets().left;
	}
	
	public static int contentOffsetY() {
		return insets().top;
	}
	
	/** Outer display width for a content rect of the given width. */
	public static int outerWidth(int contentWidth) {
		return Math.max(0, contentWidth) + insets().horizontal();
	}
	
	/** Outer display height for a content rect of the given height. */
	public static int outerHeight(int contentHeight) {
		return Math.max(0, contentHeight) + insets().vertical();
	}
	
	/**
	 * Content width recoverable from outer width (inverse of {@link #outerWidth}).
	 */
	public static int contentWidth(int outerWidth) {
		return Math.max(0, outerWidth - insets().horizontal());
	}
	
	public static int contentHeight(int outerHeight) {
		return Math.max(0, outerHeight - insets().vertical());
	}
	
	/**
	 * True if outer-local (x, y) lies on chrome (titlebar or border), not content.
	 * Coordinates are relative to the outer frame origin (top-left).
	 */
	public static boolean isInChrome(double x, double y, int contentWidth, int contentHeight) {
		int ow = outerWidth(contentWidth);
		int oh = outerHeight(contentHeight);
		if(x < 0 || y < 0 || x >= ow || y >= oh) {
			return false;
		}
		int cx = contentOffsetX();
		int cy = contentOffsetY();
		return x < cx || y < cy || x >= cx + contentWidth || y >= cy + contentHeight;
	}
	
	/** True if outer-local point is on the titlebar band. */
	public static boolean isInTitlebar(double x, double y, int contentWidth) {
		return y >= 0 && y < contentOffsetY() && x >= 0 && x < outerWidth(contentWidth);
	}
	
	/**
	 * Map outer-local coordinates to content-local (geometry-local for the client).
	 * Points on chrome yield coordinates outside the content rect.
	 */
	public static double toContentLocalX(double outerLocalX) {
		return outerLocalX - contentOffsetX();
	}
	
	public static double toContentLocalY(double outerLocalY) {
		return outerLocalY - contentOffsetY();
	}
	
}
