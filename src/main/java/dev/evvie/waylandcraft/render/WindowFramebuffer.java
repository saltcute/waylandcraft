package dev.evvie.waylandcraft.render;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Optional;

import org.joml.Matrix4fc;
import org.joml.Vector4f;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.evvie.waylandcraft.IntegerScale;
import dev.evvie.waylandcraft.SurfaceBakePlacement;
import dev.evvie.waylandcraft.WaylandCraftCommon;
import dev.evvie.waylandcraft.bridge.WLCSurface;
import dev.evvie.waylandcraft.bridge.WLCSurface.SurfaceDamage;
import dev.evvie.waylandcraft.bridge.WLCSurface.ViewportSource;
import dev.evvie.waylandcraft.displays.FramebufferRenderable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.DynamicUniformStorage.DynamicUniform;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

public class WindowFramebuffer implements FramebufferRenderable {
	
	private static final BindGroupLayout WINDOW_BIND_GROUP = BindGroupLayout.builder()
			.withSampler("sampler")
			.withUniform("window_info", UniformType.UNIFORM_BUFFER)
			.build();
	
	private static final BindGroupLayout UNPREMULTIPLY_BIND_GROUP = BindGroupLayout.builder()
			.withSampler("sampler")
			.build();
	
	private static final BindGroupLayout DAMAGE_BIND_GROUP = BindGroupLayout.builder()
			.withUniform("window_info", UniformType.UNIFORM_BUFFER)
			.build();
	
	public static final RenderPipeline WINDOW_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder()
		.withLocation(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pipeline/window"))
		.withVertexShader(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "window"))
		.withFragmentShader(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "window"))
		.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
		.withPrimitiveTopology(PrimitiveTopology.QUADS)
		.withBindGroupLayout(WINDOW_BIND_GROUP)
		.withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA)))
		.withCull(false)
		.build()
	);
	
	public static final RenderPipeline UNPREMULTIPLY_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder()
		.withLocation(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pipeline/unpremultiply"))
		.withVertexShader("core/screenquad")
		.withFragmentShader(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "unpremultiply"))
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.withColorTargetState(ColorTargetState.DEFAULT)
		.withBindGroupLayout(BindGroupLayouts.GLOBALS)
		.withBindGroupLayout(UNPREMULTIPLY_BIND_GROUP)
		.build()
	);
	
	public static final RenderPipeline DAMAGE_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder()
		.withLocation(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pipeline/damage"))
		.withVertexShader(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "window"))
		.withFragmentShader(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "window_damage"))
		.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
		.withPrimitiveTopology(PrimitiveTopology.QUADS)
		.withBindGroupLayout(DAMAGE_BIND_GROUP)
		.withColorTargetState(new ColorTargetState(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA)))
		.withCull(false)
		.build()
	);
	
	private static DynamicUniformStorage<WindowInfoUniform> uniformStorage = null;
	private static boolean debugDamage = false;
	
	public final WLCSurface surfaceTree;
	private TextureTarget tempTarget = null;
	private TextureTarget target = null;
	private FramebufferTexture texture = null;
	private Identifier location = null;
	
	/** Logical composite size (layout / world footprint / HUD placement). */
	private int logicalWidth = 0;
	private int logicalHeight = 0;
	/** GPU composite size in pixels (logical × scale); retains HiDPI buffer resolution. */
	private int pixelWidth = 0;
	private int pixelHeight = 0;
	private int compositeScale = 1;
	/** Hard cap so a bad size/scale never freezes the GPU with multi-GB targets. */
	private static final int MAX_COMPOSITE_EDGE = 8192;
	/** Logical origin offsets of the surface tree. */
	private int xoff;
	private int yoff;
	
	public WindowFramebuffer(WLCSurface surfaceTree) {
		this.surfaceTree = surfaceTree;
	}
	
	public static void endFrame() {
		if(uniformStorage != null) uniformStorage.endFrame();
	}
	
	private static void ensureUniformStorage() {
		if(uniformStorage == null) {
			uniformStorage = new DynamicUniformStorage<WindowInfoUniform>("window framebuffer", WindowInfoUniform.SIZE, 2);
		}
	}
	
	/**
	 * Shipped sizing for the window composite GPU targets.
	 * World footprint must use {@code logicalW/H}; GPU targets use {@code pixelW/H}.
	 */
	public static int[] compositePixelSize(int logicalW, int logicalH, int scale) {
		int s = IntegerScale.clamp(scale);
		return new int[] {
				IntegerScale.compositePixels(logicalW, s),
				IntegerScale.compositePixels(logicalH, s)
		};
	}
	
	/**
	 * Composite GPU scale from actual client buffer_scale only.
	 * Must match bakeSurface placement (also per-surface buffer_scale) so the
	 * target is fully filled — never size from outputScale alone while buffers
	 * are still 1× (would leave ~3/4 of the texture empty).
	 */
	private int resolveCompositeScale() {
		int maxBufferScale = 1;
		for(WLCSurface surface = surfaceTree; surface != null; surface = surface.getNextChild()) {
			maxBufferScale = Math.max(maxBufferScale, surface.getBufferScale());
		}
		return IntegerScale.resolveCompositeScale(maxBufferScale);
	}
	
	private void updateTarget() {
		int minX = 0;
		int minY = 0;
		int maxX = 0;
		int maxY = 0;
		
		int walkGuard = 0;
		for(WLCSurface surface = surfaceTree; surface != null && walkGuard < 256; surface = surface.getNextChild(), walkGuard++) {
			int sMinX = surface.xSubpos;
			int sMinY = surface.ySubpos;
			int sMaxX = sMinX + surface.width();
			int sMaxY = sMinY + surface.height();
			
			if(sMinX < minX) minX = sMinX;
			if(sMinY < minY) minY = sMinY;
			if(sMaxX > maxX) maxX = sMaxX;
			if(sMaxY > maxY) maxY = sMaxY;
		}
		
		int prevPixelW = pixelWidth;
		int prevPixelH = pixelHeight;
		
		this.xoff = -minX;
		this.yoff = -minY;
		this.logicalWidth = maxX - minX;
		this.logicalHeight = maxY - minY;
		this.compositeScale = resolveCompositeScale();
		
		int[] pixels = compositePixelSize(logicalWidth, logicalHeight, compositeScale);
		this.pixelWidth = Math.min(pixels[0], MAX_COMPOSITE_EDGE);
		this.pixelHeight = Math.min(pixels[1], MAX_COMPOSITE_EDGE);
		
		if(logicalWidth <= 0 || logicalHeight <= 0 || pixelWidth <= 0 || pixelHeight <= 0) {
			destroy();
			return;
		}
		
		if(pixelWidth != prevPixelW || pixelHeight != prevPixelH) destroy();
		
		if(tempTarget == null) {
			tempTarget = new TextureTarget(name() + "-temp", pixelWidth, pixelHeight, false, GpuFormat.RGBA8_UNORM);
		}
		
		if(target == null) {
			target = new TextureTarget(name(), pixelWidth, pixelHeight, false, GpuFormat.RGBA8_UNORM);
		}
		
		if(texture == null) registerTexture();
	}
	
	private String name() {
		return "wayland-framebuffer-" + this.hashCode() + "-" + surfaceTree.hashCode();
	}
	
	public void render() {
		updateTarget();
		if(target == null || tempTarget == null) return;
		
		PoseStack poseStack = new PoseStack();
		poseStack.translate(-1.0, -1.0, 0.0);
		// Map pixel-space quads into NDC for the high-res composite target.
		poseStack.scale(2.0f / pixelWidth, 2.0f / pixelHeight, 1.0f);
		
		ArrayList<CompiledBufferDraw> elements = new ArrayList<>();
		int bakeGuard = 0;
		for(WLCSurface surface = surfaceTree; surface != null && bakeGuard < 256; surface = surface.getNextChild(), bakeGuard++) {
			BufferDraw draw = bakeSurface(surface, xoff + surface.xSubpos, yoff + surface.ySubpos);
			if(draw != null) elements.add(draw.compile());
		}
		
		ensureUniformStorage();
		GpuBufferSlice alphaUniforms = uniformStorage.writeUniform(new WindowInfoUniform(poseStack.last().pose(), true));
		GpuBufferSlice opaqueUniforms = uniformStorage.writeUniform(new WindowInfoUniform(poseStack.last().pose(), false));
		
		try {
			try(RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "window framebuffer", tempTarget.getColorTextureView(), Optional.of(new Vector4f(0.0f, 0.0f, 0.0f, 0.0f)))) {
				pass.setPipeline(WINDOW_PIPELINE);
				for(CompiledBufferDraw element : elements) {
					pass.setUniform("window_info", element.alpha ? alphaUniforms : opaqueUniforms);
					pass.bindTexture("sampler", element.textureView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
					pass.setVertexBuffer(0, element.vertexBuffer.slice());
					pass.setIndexBuffer(element.indexBuffer, element.indexType);
					pass.drawIndexed(element.indexCount, 1, 0, 0, 0);
				}
			}
		}
		finally {
			for(CompiledBufferDraw element : elements) {
				element.vertexBuffer.close();
			}
		}
		
		if(debugDamage) drawDebugDamage(opaqueUniforms);
		
		try(RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "window framebuffer unpremultiply", target.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(UNPREMULTIPLY_PIPELINE);
			pass.bindTexture("sampler", tempTarget.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}
	
	private void drawDebugDamage(GpuBufferSlice opaqueUniforms) {
		ArrayList<CompiledBufferDraw> damageElements = new ArrayList<>();
		int dmgScale = IntegerScale.clamp(this.compositeScale);
		for(WLCSurface surface = surfaceTree; surface != null; surface = surface.getNextChild()) {
			int sx = (int) IntegerScale.logicalToBuffer(xoff + surface.xSubpos, dmgScale);
			int sy = (int) IntegerScale.logicalToBuffer(yoff + surface.ySubpos, dmgScale);
			
			for(SurfaceDamage damage : surface.getDamage()) {
				float dx = (float) IntegerScale.logicalToBuffer(damage.x(), dmgScale);
				float dy = (float) IntegerScale.logicalToBuffer(damage.y(), dmgScale);
				float dw = (float) IntegerScale.logicalToBuffer(damage.width(), dmgScale);
				float dh = (float) IntegerScale.logicalToBuffer(damage.height(), dmgScale);
				damageElements.add(new BufferDraw(null, sx + dx, sy + dy, dw, dh, 0, 0, 0, 0, false).compile());
			}
		}
		
		try {
			try(RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "window framebuffer damage", tempTarget.getColorTextureView(), Optional.empty())) {
				pass.setPipeline(DAMAGE_PIPELINE);
				pass.setUniform("window_info", opaqueUniforms);
				for(CompiledBufferDraw element : damageElements) {
					pass.setVertexBuffer(0, element.vertexBuffer.slice());
					pass.setIndexBuffer(element.indexBuffer, element.indexType);
					pass.drawIndexed(element.indexCount, 1, 0, 0, 0);
				}
			}
		}
		finally {
			for(CompiledBufferDraw element : damageElements) {
				element.vertexBuffer.close();
			}
		}
	}
	
	/**
	 * Bake a surface into the high-res composite.
	 * Placement uses {@link #compositeScale} (same as GPU target sizing) so scale ≥ 2
	 * fills the content area. UVs use the surface buffer_scale via {@link SurfaceBakePlacement}.
	 */
	private BufferDraw bakeSurface(WLCSurface surface, float logicalX, float logicalY) {
		BufferTexture buf = surface.getBuffer();
		if(buf == null) return null;
		
		ViewportSource src = surface.getViewportSource();
		boolean hasVp = src != null;
		SurfaceBakePlacement.BakeQuad q = SurfaceBakePlacement.place(
				logicalX, logicalY,
				surface.width(), surface.height(),
				this.compositeScale,
				buf.width, buf.height,
				surface.getBufferScale(),
				hasVp,
				hasVp ? src.x() : 0, hasVp ? src.y() : 0,
				hasVp ? src.width() : 0, hasVp ? src.height() : 0
		);
		
		return new BufferDraw(
				buf.getTextureView(),
				q.x(), q.y(), q.w(), q.h(),
				q.u1(), q.v1(), q.u2(), q.v2(),
				buf.format != BufferTexture.FORMAT_XRGB8888
		);
	}
	
	private static record CompiledBufferDraw(GpuTextureView textureView, GpuBuffer vertexBuffer, GpuBuffer indexBuffer, int indexCount, IndexType indexType, boolean alpha) {
	}
	
	private static record BufferDraw(GpuTextureView textureView, float x, float y, float w, float h, float u1, float v1, float u2, float v2, boolean alpha) {
		
		public CompiledBufferDraw compile() {
			try(ByteBufferBuilder byteBuilder = new ByteBufferBuilder(DefaultVertexFormat.POSITION_TEX.getVertexSize() * 4)) {
				BufferBuilder builder = new BufferBuilder(byteBuilder, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX);
				builder.addVertex(x, y, 0).setUv(u1, v1);
				builder.addVertex(x + w, y, 0).setUv(u2, v1);
				builder.addVertex(x + w, y + h, 0).setUv(u2, v2);
				builder.addVertex(x, y + h, 0).setUv(u1, v2);
				
				try(MeshData mesh = builder.buildOrThrow()) {
					int indexCount = mesh.drawState().indexCount();
					RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
					GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(null, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, mesh.vertexBuffer());
					GpuBuffer indexBuffer = indices.getBuffer(indexCount);
					return new CompiledBufferDraw(textureView, vertexBuffer, indexBuffer, indexCount, indices.type(), alpha);
				}
			}
		}
		
	}
	
	private void registerTexture() {
		if(target == null) return;
		
		texture = new FramebufferTexture(getTextureView());
		location = Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, name());
		
		Minecraft.getInstance().getTextureManager().register(location, texture);
	}
	
	private void unregisterTexture() {
		TextureManager manager = Minecraft.getInstance().getTextureManager();
		manager.register(location, manager.getTexture(MissingTextureAtlasSprite.getLocation()));
		texture = null;
		location = null;
	}
	
	public void destroy() {
		if(target != null) target.destroyBuffers();
		if(tempTarget != null) tempTarget.destroyBuffers();
		if(texture != null) unregisterTexture();
		target = null;
		tempTarget = null;
		// logical/pixel dims are recomputed on next updateTarget; leave them so
		// getWidth still reports last logical size until rebuild if needed.
	}
	
	/**
	 * Logical width of the composited window tree. Used for world footprint and
	 * HUD placement so raising scale does not grow the in-world size.
	 */
	@Override
	public int getWidth() {
		return logicalWidth;
	}
	
	/** Logical height of the composited window tree. */
	@Override
	public int getHeight() {
		return logicalHeight;
	}
	
	/** Pixel width of the GPU composite (logical × scale). */
	public int getPixelWidth() {
		return pixelWidth;
	}
	
	/** Pixel height of the GPU composite (logical × scale). */
	public int getPixelHeight() {
		return pixelHeight;
	}
	
	public int getCompositeScale() {
		return compositeScale;
	}
	
	@Override
	public int getXOff() {
		return xoff;
	}
	
	@Override
	public int getYOff() {
		return yoff;
	}
	
	public GpuTextureView getTextureView() {
		if(target == null) return null;
		return target.getColorTextureView();
	}
	
	public Identifier getTextureLocation() {
		return location;
	}
	
	public boolean isValid() {
		return target != null;
	}
	
	private static class FramebufferTexture extends AbstractTexture {
		
		public FramebufferTexture(GpuTextureView textureView) {
			this.textureView = textureView;
			this.texture = textureView.texture();
			this.sampler = RenderUtils.WINDOW_SAMPLER.get();
		}
		
		@Override
		public void close() {
		}
		
	}
	
	private static record WindowInfoUniform(Matrix4fc mat, boolean alpha) implements DynamicUniform {
		
		public static final int SIZE = new Std140SizeCalculator().putMat4f().putFloat().get();
		
		@Override
		public void write(ByteBuffer byteBuffer) {
			Std140Builder.intoBuffer(byteBuffer).putMat4f(mat).putFloat(alpha ? 0.0f : 1.0f);
		}
		
	}
	
}
