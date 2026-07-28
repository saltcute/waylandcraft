package dev.evvie.waylandcraft.bridge;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import dev.evvie.waylandcraft.IntegerScale;
import dev.evvie.waylandcraft.WaylandCraft;
import dev.evvie.waylandcraft.render.BufferTexture;
import dev.evvie.waylandcraft.render.BufferTexture.DmabufTexture;
import dev.evvie.waylandcraft.render.BufferTexture.ShmBufferTexture;
import dev.evvie.waylandcraft.render.BufferTexture.SinglePixelBufferTexture;
import net.minecraft.util.Mth;

public class WLCSurface {
	
	// Set to zero when this surface no longer exists
	private long handle;
	
	// Used by native code to tag used surfaces
	protected boolean visited;
	
	@Nullable
	private BufferTexture buffer = null;
	
	// Either a child of this surface or one of its siblings
	@Nullable
	protected WLCSurface nextChild = null;
	
	@Nullable
	protected WLCSurface prevChild = null;
	
	protected long parentHandle = 0;
	
	@Nullable
	protected WLCSurface parent = null;
	
	// Logical surface size (buffer pixels / buffer_scale, or viewport dst).
	private int width = 0;
	private int height = 0;
	
	/** Client wl_surface buffer_scale used for logical size and damage mapping. */
	private int bufferScale = 1;
	
	@Nullable
	private ViewportSource sourceView = null;
	
	// X and Y offsets relative to parent coords
	protected int xoff = 0;
	protected int yoff = 0;
	
	// Total calculated offsets
	public int xSubpos = 0;
	public int ySubpos = 0;
	
	private ArrayList<SurfaceDamage> damage = new ArrayList<>();
	
	protected WLCSurface(long handle) {
		this.handle = handle;
	}
	
	protected long getHandle() {
		return this.handle;
	}
	
	protected long takeHandle() {
		long old = this.handle;
		this.handle = 0;
		return old;
	}
	
	public boolean isAlive() {
		return handle != 0;
	}
	
	// Attach a shared memory buffer (texture keeps buffer-pixel dimensions).
	// Logical surface size is applied via setLogicalSize / setBufferScale from native.
	protected void attachShmBuffer(long ptr, int width, int height, int format, int stride) {
		if(this.buffer != null) {
			this.buffer.release();
		}
		this.buffer = new ShmBufferTexture(ptr, width, height, format, stride);
		applyLogicalSizeFromBuffer(width, height);
	}
	
	// Attach a single pixel buffer
	// The surface width and height are reset to 1 logical pixel.
	protected void attachSinglePixelBuffer(byte r, byte g, byte b, byte a) {
		if(this.buffer != null) {
			this.buffer.release();
		}
		this.buffer = new SinglePixelBufferTexture(r, g, b, a);
		applyLogicalSizeFromBuffer(1, 1);
	}
	
	// Attach an already known dmabuf
	// Returns false if no DmabufTexture by that handle was found.
	protected boolean attachDmabuf(long handle) {
		if(this.buffer != null) {
			this.buffer.release();
		}
		
		this.buffer = WaylandCraft.instance.bridge.getDmabuf(handle);
		if(this.buffer != null) {
			applyLogicalSizeFromBuffer(buffer.width, buffer.height);
			
			DmabufTexture dmabuf = (DmabufTexture) this.buffer;
			dmabuf.copyData();
		}
		return this.buffer != null;
	}
	
	// Create and attach a new DmabufTexture
	// MUST only be used when attachDmabuf returns false for this handle!
	protected void attachNewDmabuf(long handle, long eglImage, int width, int height) {
		DmabufTexture dmabuf = new DmabufTexture(handle, eglImage, width, height);
		WaylandCraft.instance.bridge.addDmabuf(dmabuf);
		
		if(!attachDmabuf(handle)) {
			throw new RuntimeException("Failed to attach newly created dmabuf");
		}
	}
	
	protected void removeBuffer() {
		this.buffer = null;
		this.width = this.height = 0;
		this.bufferScale = 1;
	}
	
	/** Called from native with the client's wl_surface buffer_scale. */
	protected void setBufferScale(int scale) {
		this.bufferScale = IntegerScale.clamp(scale);
		if(this.buffer != null) {
			applyLogicalSizeFromBuffer(this.buffer.width, this.buffer.height);
		}
	}
	
	/** Explicit logical size (e.g. after computing buffer / scale in native). */
	protected void setLogicalSize(int width, int height) {
		this.width = Math.max(0, width);
		this.height = Math.max(0, height);
	}
	
	private void applyLogicalSizeFromBuffer(int bufferWidth, int bufferHeight) {
		this.width = IntegerScale.bufferToLogical(bufferWidth, this.bufferScale);
		this.height = IntegerScale.bufferToLogical(bufferHeight, this.bufferScale);
	}
	
	// Set viewport source crop (smithay ViewportCachedState.src is already Logical /
	// surface-local after buffer_scale). When dst is not set later, logical size is
	// the src size as-is — do not divide by bufferScale again.
	protected void setViewportSrc(double x, double y, double width, double height) {
		this.sourceView = new ViewportSource(x, y, width, height);
		this.width = (int) width;
		this.height = (int) height;
	}
	
	// Set viewport destination dimensions (logical surface coordinates).
	// Overrides this surface's width & height values.
	protected void setViewportDst(int width, int height) {
		this.width = width;
		this.height = height;
	}
	
	/** Clear viewport crop/dst so size falls back to buffer / buffer_scale. */
	protected void clearViewport() {
		this.sourceView = null;
		if(this.buffer != null) {
			applyLogicalSizeFromBuffer(this.buffer.width, this.buffer.height);
		}
	}
	
	protected void clearDamage() {
		damage.clear();
	}
	
	protected void addSurfaceDamage(int x, int y, int width, int height) {
		this.damage.add(new SurfaceDamage(x, y, width, height));
	}
	
	protected void addBufferDamage(int x, int y, int width, int height) {
		if(buffer == null) return;
		
		double sx = x;
		double sy = y;
		double sw = width;
		double sh = height;
		
		if(sourceView != null) {
			sx -= sourceView.x;
			sy -= sourceView.y;
		}
		
		sx *= this.width / buffer.width;
		sy *= this.height / buffer.height;
		sw *= this.width / buffer.width;
		sh *= this.height / buffer.height;
		
		addSurfaceDamage(Mth.floor(sx), Mth.floor(sy), Mth.ceil(sw), Mth.ceil(sh));
	}
	
	public List<SurfaceDamage> getDamage() {
		return damage;
	}
	
	public int width() {
		return width;
	}
	
	public int height() {
		return height;
	}
	
	/** Client {@code wl_surface} buffer_scale (always ≥ 1). */
	public int getBufferScale() {
		return bufferScale;
	}
	
	/**
	 * Pixel size of this surface in the high-res window composite
	 * (logical × buffer_scale, or attached buffer dimensions when present).
	 */
	public int compositeWidth() {
		if(buffer != null) return buffer.width;
		return IntegerScale.compositePixels(width, bufferScale);
	}
	
	public int compositeHeight() {
		if(buffer != null) return buffer.height;
		return IntegerScale.compositePixels(height, bufferScale);
	}
	
	public ViewportSource getViewportSource() {
		return sourceView;
	}
	
	@Nullable
	public BufferTexture getBuffer() {
		return this.buffer;
	}
	
	@Nullable
	public WLCSurface getParent() {
		return this.parent;
	}
	
	@Nullable
	public WLCSurface getNextChild() {
		return this.nextChild;
	}
	
	@Nullable
	public WLCSurface getPrevChild() {
		return this.prevChild;
	}
	
	// Surface-local dimensions of the source rectangle in a buffer
	public static final record ViewportSource(double x, double y, double width, double height) {
	}
	
	// Surface-local region describing contents damage
	public static final record SurfaceDamage(int x, int y, int width, int height) {
	}
	
}
