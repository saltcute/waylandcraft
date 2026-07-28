package dev.evvie.waylandcraft.render;

import java.util.function.Function;
import java.util.function.Supplier;

import org.joml.Vector3f;
import org.joml.Vector4f;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.evvie.waylandcraft.WaylandCraftCommon;
import dev.evvie.waylandcraft.compat.IrisCompat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

public class RenderUtils {
	
	private static final RenderPipeline.Snippet WINDOW_PIPELINE_SNIPPET = RenderPipeline.builder()
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
			.withVertexShader(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "core/rendertype_window"))
			.withFragmentShader(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "core/rendertype_window"))
			.withDepthStencilState(DepthStencilState.DEFAULT)
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.buildSnippet();
	
	private static final RenderPipeline WINDOW_CUTOUT_PIPELINE = RenderPipeline.builder(WINDOW_PIPELINE_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pipeline/window_cutout"))
			.withShaderDefine("ALPHA_CUTOUT")
			.build();
	
	private static final RenderPipeline WINDOW_TRANSLUCENT_PIPELINE = RenderPipeline.builder(WINDOW_PIPELINE_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pipeline/window_translucent"))
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.build();
	
	private static final RenderPipeline WINDOW_CUTOUT_ANTIALIASING_PIPELINE = RenderPipeline.builder(WINDOW_PIPELINE_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pipeline/window_cutout"))
			.withShaderDefine("ALPHA_CUTOUT")
			.withShaderDefine("RGSS")
			.build();
	
	private static final RenderPipeline WINDOW_TRANSLUCENT_ANTIALIASING_PIPELINE = RenderPipeline.builder(WINDOW_PIPELINE_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pipeline/window_translucent"))
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withShaderDefine("RGSS")
			.build();
	
	private static final RenderPipeline WINDOW_CUTOUT_BACKGROUND_PIPELINE = RenderPipeline.builder(WINDOW_PIPELINE_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pipeline/window_cutout_background"))
			.withShaderDefine("ALPHA_CUTOUT")
			.withShaderDefine("NO_COLOR")
			.build();
	
	private static final RenderPipeline WINDOW_TRANSLUCENT_BACKGROUND_PIPELINE = RenderPipeline.builder(WINDOW_PIPELINE_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "pipeline/window_translucent_background"))
			.withShaderDefine("NO_COLOR")
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.build();
	
	public static final Supplier<GpuSampler> WINDOW_SAMPLER = () -> RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.NEAREST, false);
	
	public static final Function<Identifier, RenderType> WINDOW_CUTOUT = Util.memoize(
		(identifier) -> {
			RenderSetup setup = RenderSetup.builder(WINDOW_CUTOUT_PIPELINE)
					.withTexture("Sampler0", identifier, WINDOW_SAMPLER)
					.createRenderSetup();
			return RenderType.create("window_cutout", setup);
		}
	);
	
	public static final Function<Identifier, RenderType> WINDOW_TRANSLUCENT = Util.memoize(
		(identifier) -> {
			RenderSetup setup = RenderSetup.builder(WINDOW_TRANSLUCENT_PIPELINE)
					.withTexture("Sampler0", identifier, WINDOW_SAMPLER)
					.createRenderSetup();
			return RenderType.create("window_translucent", setup);
		}
	);
	
	public static final Function<Identifier, RenderType> WINDOW_CUTOUT_ANTIALIAS = Util.memoize(
		(identifier) -> {
			RenderSetup setup = RenderSetup.builder(WINDOW_CUTOUT_ANTIALIASING_PIPELINE)
					.withTexture("Sampler0", identifier, WINDOW_SAMPLER)
					.createRenderSetup();
			return RenderType.create("window_cutout_antialias", setup);
		}
	);
	
	public static final Function<Identifier, RenderType> WINDOW_TRANSLUCENT_ANTIALIAS = Util.memoize(
		(identifier) -> {
			RenderSetup setup = RenderSetup.builder(WINDOW_TRANSLUCENT_ANTIALIASING_PIPELINE)
					.withTexture("Sampler0", identifier, WINDOW_SAMPLER)
					.createRenderSetup();
			return RenderType.create("window_translucent_antialias", setup);
		}
	);
	
	public static final Function<Identifier, RenderType> WINDOW_BACKGROUND_CUTOUT = Util.memoize(
		(identifier) -> {
			RenderSetup setup = RenderSetup.builder(WINDOW_CUTOUT_BACKGROUND_PIPELINE)
					.withTexture("Sampler0", identifier, WINDOW_SAMPLER)
					.createRenderSetup();
			return RenderType.create("window_cutout_background", setup);
		}
	);
	
	public static final Function<Identifier, RenderType> WINDOW_BACKGROUND_TRANSLUCENT = Util.memoize(
		(identifier) -> {
			RenderSetup setup = RenderSetup.builder(WINDOW_TRANSLUCENT_BACKGROUND_PIPELINE)
					.withTexture("Sampler0", identifier, WINDOW_SAMPLER)
					.createRenderSetup();
			return RenderType.create("window_translucent_background", setup);
		}
	);
	
	public static void renderFramebuffer(WindowFramebuffer framebuffer, PoseStack poseStack, SubmitNodeCollector collector, boolean cutout, Vec3 origin, Vec3 spanX, Vec3 spanY) {
		if(!framebuffer.isValid()) return;
		
		if(IrisCompat.isShaderActive()) {
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutCull(framebuffer.getTextureLocation()), new FramebufferRenderInstanceEntity(origin, spanX, spanY, ARGB.white(1.0f), OverlayTexture.NO_OVERLAY, LightCoordsUtil.FULL_BRIGHT, false));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutCull(framebuffer.getTextureLocation()), new FramebufferRenderInstanceEntity(origin, spanX, spanY, ARGB.black(1.0f), OverlayTexture.NO_OVERLAY, LightCoordsUtil.FULL_BRIGHT, true));
			return;
		}
		
		Function<Identifier, RenderType> renderType;
		
		final boolean antialiasing = true;
		
		// Front quad
		if(antialiasing) renderType = cutout ? WINDOW_CUTOUT_ANTIALIAS : WINDOW_TRANSLUCENT_ANTIALIAS;
		else renderType = cutout ? WINDOW_CUTOUT : WINDOW_TRANSLUCENT;
		collector.submitCustomGeometry(poseStack, renderType.apply(framebuffer.getTextureLocation()), new FramebufferRenderInstance(origin, spanX, spanY, false));
		
		// Back quad
		renderType = cutout ? WINDOW_BACKGROUND_CUTOUT : WINDOW_BACKGROUND_TRANSLUCENT;
		collector.submitCustomGeometry(poseStack, renderType.apply(framebuffer.getTextureLocation()), new FramebufferRenderInstance(origin, spanX, spanY, true));
	}
	
	public static final record FramebufferRenderInstance(Vec3 origin, Vec3 spanX, Vec3 spanY, boolean reverse) implements CustomGeometryRenderer {
		
		@Override
		public void render(Pose pose, VertexConsumer buffer) {
			Vec3 tl = origin;
			Vec3 bl = tl.add(spanY);
			Vec3 br = bl.add(spanX);
			Vec3 tr = tl.add(spanX);
			
			if(!reverse) {
				buffer.addVertex(pose, tl.toVector3f()).setUv(0.0f, 0.0f);
				buffer.addVertex(pose, bl.toVector3f()).setUv(0.0f, 1.0f);
				buffer.addVertex(pose, br.toVector3f()).setUv(1.0f, 1.0f);
				buffer.addVertex(pose, tr.toVector3f()).setUv(1.0f, 0.0f);
			}
			else {
				buffer.addVertex(pose, tr.toVector3f()).setUv(1.0f, 0.0f);
				buffer.addVertex(pose, br.toVector3f()).setUv(1.0f, 1.0f);
				buffer.addVertex(pose, bl.toVector3f()).setUv(0.0f, 1.0f);
				buffer.addVertex(pose, tl.toVector3f()).setUv(0.0f, 0.0f);
			}
		}
		
	}
	
	public static final record FramebufferRenderInstanceEntity(Vec3 origin, Vec3 spanX, Vec3 spanY, int color, int overlayCoords, int light, boolean reverse) implements CustomGeometryRenderer {
		
		@Override
		public void render(Pose pose, VertexConsumer buffer) {
			Vec3 tl = origin;
			Vec3 bl = tl.add(spanY);
			Vec3 br = bl.add(spanX);
			Vec3 tr = tl.add(spanX);
			Vec3 normal = spanY.cross(spanX).normalize();
			
			Vector4f pos1 = pose.pose().transform(new Vector4f((float) tl.x, (float) tl.y, (float) tl.z, 1.0f));
			Vector4f pos2 = pose.pose().transform(new Vector4f((float) bl.x, (float) bl.y, (float) bl.z, 1.0f));
			Vector4f pos3 = pose.pose().transform(new Vector4f((float) br.x, (float) br.y, (float) br.z, 1.0f));
			Vector4f pos4 = pose.pose().transform(new Vector4f((float) tr.x, (float) tr.y, (float) tr.z, 1.0f));
			
			Vector3f norm = pose.transformNormal(normal.toVector3f(), new Vector3f());
			
			if(!reverse) {
				buffer.addVertex(pos1.x, pos1.y, pos1.z, color, 0.0f, 0.0f, overlayCoords, light, norm.x, norm.y, norm.z);
				buffer.addVertex(pos2.x, pos2.y, pos2.z, color, 0.0f, 1.0f, overlayCoords, light, norm.x, norm.y, norm.z);
				buffer.addVertex(pos3.x, pos3.y, pos3.z, color, 1.0f, 1.0f, overlayCoords, light, norm.x, norm.y, norm.z);
				buffer.addVertex(pos4.x, pos4.y, pos4.z, color, 1.0f, 0.0f, overlayCoords, light, norm.x, norm.y, norm.z);
			}
			else {
				buffer.addVertex(pos4.x, pos4.y, pos4.z, color, 1.0f, 0.0f, overlayCoords, light, norm.x, norm.y, norm.z);
				buffer.addVertex(pos3.x, pos3.y, pos3.z, color, 1.0f, 1.0f, overlayCoords, light, norm.x, norm.y, norm.z);
				buffer.addVertex(pos2.x, pos2.y, pos2.z, color, 0.0f, 1.0f, overlayCoords, light, norm.x, norm.y, norm.z);
				buffer.addVertex(pos1.x, pos1.y, pos1.z, color, 0.0f, 0.0f, overlayCoords, light, norm.x, norm.y, norm.z);
			}
		}
		
	}
	
	public static void renderFramebuffer2D(GuiGraphicsExtractor context, WindowFramebuffer framebuffer, int x, int y, int w, int h) {
		if(!framebuffer.isValid()) return;
		context.blit(framebuffer.getTextureLocation(), x, y, x + w, y + h, 0.0f, 1.0f, 0.0f, 1.0f);
	}
	
	/**
	 * 2D SSD chrome for window-manager / HUD paths. Same layout as
	 * {@link #renderServerChrome} (titlebar + borders + accent) via GUI fills.
	 * {@code outerX}/{@code outerY} are the outer-frame top-left in the same
	 * pixel basis as {@link #renderFramebuffer2D}.
	 */
	public static void renderServerChrome2D(GuiGraphicsExtractor context, int outerX, int outerY, int contentWidth, int contentHeight) {
		int outerW = dev.evvie.waylandcraft.ServerDecorationChrome.outerWidth(contentWidth);
		int outerH = dev.evvie.waylandcraft.ServerDecorationChrome.outerHeight(contentHeight);
		int titleH = dev.evvie.waylandcraft.ServerDecorationChrome.TITLEBAR_HEIGHT;
		int contentOx = dev.evvie.waylandcraft.ServerDecorationChrome.contentOffsetX();
		int contentOy = dev.evvie.waylandcraft.ServerDecorationChrome.contentOffsetY();
		
		// Outer frame backdrop (sides/bottom remain as border around content)
		context.fill(outerX, outerY, outerX + outerW, outerY + outerH,
				dev.evvie.waylandcraft.ServerDecorationChrome.BORDER_COLOR);
		// Titlebar
		context.fill(outerX, outerY, outerX + outerW, outerY + titleH,
				dev.evvie.waylandcraft.ServerDecorationChrome.TITLEBAR_COLOR);
		// Accent under titlebar
		int accentH = 2;
		context.fill(outerX, outerY + titleH - accentH, outerX + outerW, outerY + titleH,
				dev.evvie.waylandcraft.ServerDecorationChrome.ACCENT_COLOR);
		// Dark underlay in content hole
		context.fill(outerX + contentOx, outerY + contentOy,
				outerX + contentOx + Math.max(0, contentWidth),
				outerY + contentOy + Math.max(0, contentHeight),
				0xFF0D0D0D);
	}
	
	public static void renderLineStrip(PoseStack poseStack, SubmitNodeCollector collector, Vec3[] points, int color, float width) {
		collector.submitCustomGeometry(poseStack, RenderTypes.lines(), new LineStripDraw(points, color, width));
	}
	
	/**
	 * Draw a solid-color axis-aligned quad in the window plane (local pixel basis).
	 * {@code origin} is the top-left corner; {@code spanX}/{@code spanY} are the
	 * full edge vectors (already scaled by localX/localY * pixel size).
	 */
	public static void renderSolidQuad(PoseStack poseStack, SubmitNodeCollector collector, Vec3 origin, Vec3 spanX, Vec3 spanY, int argb) {
		collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads(), new SolidQuadDraw(origin, spanX, spanY, argb));
	}
	
	/**
	 * Paint minimal SSD chrome around client content: titlebar, borders, accent.
	 * {@code outerOrigin} is the outer-frame top-left in the same basis as window
	 * geometry origin; {@code localX}/{@code localY} are one-pixel world vectors.
	 * {@code contentWidth}/{@code contentHeight} are the client content size in
	 * logical pixels (not including chrome).
	 */
	public static void renderServerChrome(PoseStack poseStack, SubmitNodeCollector collector, Vec3 outerOrigin, Vec3 localX, Vec3 localY, int contentWidth, int contentHeight) {
		int outerW = dev.evvie.waylandcraft.ServerDecorationChrome.outerWidth(contentWidth);
		int outerH = dev.evvie.waylandcraft.ServerDecorationChrome.outerHeight(contentHeight);
		int titleH = dev.evvie.waylandcraft.ServerDecorationChrome.TITLEBAR_HEIGHT;
		int contentOx = dev.evvie.waylandcraft.ServerDecorationChrome.contentOffsetX();
		int contentOy = dev.evvie.waylandcraft.ServerDecorationChrome.contentOffsetY();
		
		// Full outer frame (border color) as backdrop — sides/bottom remain visible
		// around the client content drawn on top.
		renderSolidQuad(poseStack, collector, outerOrigin, localX.scale(outerW), localY.scale(outerH),
				dev.evvie.waylandcraft.ServerDecorationChrome.BORDER_COLOR);
		
		// Titlebar band
		renderSolidQuad(poseStack, collector, outerOrigin, localX.scale(outerW), localY.scale(titleH),
				dev.evvie.waylandcraft.ServerDecorationChrome.TITLEBAR_COLOR);
		
		// Accent line under titlebar
		int accentH = 2;
		Vec3 accentOrigin = outerOrigin.add(localY.scale(titleH - accentH));
		renderSolidQuad(poseStack, collector, accentOrigin, localX.scale(outerW), localY.scale(accentH),
				dev.evvie.waylandcraft.ServerDecorationChrome.ACCENT_COLOR);
		
		// Dark underlay in the content hole so translucent clients still show a frame.
		Vec3 contentOrigin = outerOrigin.add(localX.scale(contentOx)).add(localY.scale(contentOy));
		renderSolidQuad(poseStack, collector, contentOrigin, localX.scale(Math.max(0, contentWidth)), localY.scale(Math.max(0, contentHeight)),
				0xFF0D0D0D);
	}
	
	private static final record SolidQuadDraw(Vec3 origin, Vec3 spanX, Vec3 spanY, int argb) implements CustomGeometryRenderer {
		
		@Override
		public void render(Pose pose, VertexConsumer buffer) {
			Vec3 tl = origin;
			Vec3 bl = tl.add(spanY);
			Vec3 br = bl.add(spanX);
			Vec3 tr = tl.add(spanX);
			
			buffer.addVertex(pose, tl.toVector3f()).setColor(argb);
			buffer.addVertex(pose, bl.toVector3f()).setColor(argb);
			buffer.addVertex(pose, br.toVector3f()).setColor(argb);
			buffer.addVertex(pose, tr.toVector3f()).setColor(argb);
		}
		
	}
	
	private static final record LineStripDraw(Vec3[] points, int color, float width) implements CustomGeometryRenderer {
		
		@Override
		public void render(Pose pose, VertexConsumer buffer) {
			for(int i = 1; i < points.length; i++) {
				Vec3 start = points[i - 1];
				Vec3 end = points[i];
				Vector3f normal = end.subtract(start).toVector3f();
				
				buffer.addVertex(pose, start.toVector3f()).setColor(color).setNormal(pose, normal).setLineWidth(width);
				buffer.addVertex(pose,   end.toVector3f()).setColor(color).setNormal(pose, normal).setLineWidth(width);
			}
		}
		
	}
	
}
