package dev.evvie.waylandcraft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.Platform;

import com.mojang.blaze3d.platform.InputConstants;

import dev.evvie.waylandcraft.bridge.WLCAbstractWindow;
import dev.evvie.waylandcraft.bridge.WLCAbstractWindow.SurfaceGeometry;
import dev.evvie.waylandcraft.bridge.WLCPopup;
import dev.evvie.waylandcraft.bridge.WLCSurface;
import dev.evvie.waylandcraft.bridge.WLCToplevel;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge.ResizeRequest;
import dev.evvie.waylandcraft.bridge.WaylandCraftBridge.Size;
import dev.evvie.waylandcraft.desktop.XDGDesktopManager;
import dev.evvie.waylandcraft.displays.WindowDisplay;
import dev.evvie.waylandcraft.displays.WindowDisplay.DisplayHitResult;
import dev.evvie.waylandcraft.grabs.DNDGrab;
import dev.evvie.waylandcraft.grabs.MoveGrab;
import dev.evvie.waylandcraft.grabs.PointerGrabMap;
import dev.evvie.waylandcraft.grabs.PointerGrabMap.ImplicitGrab;
import dev.evvie.waylandcraft.grabs.ResizeGrab;
import dev.evvie.waylandcraft.grabs.WindowGrab;
import dev.evvie.waylandcraft.gui.AppLauncherScreen;
import dev.evvie.waylandcraft.gui.WaylandHudRenderer;
import dev.evvie.waylandcraft.gui.WindowManagerScreen;
import dev.evvie.waylandcraft.item.WindowHandle;
import dev.evvie.waylandcraft.item.WindowItem;
import dev.evvie.waylandcraft.item.WindowItemManager;
import dev.evvie.waylandcraft.render.WindowInHandRenderer;
import dev.evvie.waylandcraft.render.WindowInItemFrameRenderer;
import dev.evvie.waylandcraft.render.model.WindowItemModel;
import dev.evvie.waylandcraft.settings.WaylandCraftSettings;
import dev.evvie.waylandcraft.settings.WaylandCraftSettingsManager;
import dev.evvie.waylandcraft.utils.CursorShape;
import dev.evvie.waylandcraft.utils.WaylandCraftUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class WaylandCraft implements ClientModInitializer {
	
	private static final KeyMapping.Category KEYBIND_CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "keys"));
	
	public static WaylandCraft instance;
	public static boolean fallbackMode = false;
	
	public WaylandCraftSettingsManager settingsManager;
	public WaylandCraftSettings settings;
	
	public WaylandCraftBridge bridge = null;
	public String waylandSocket = "";
	public @Nullable String x11Display = null;
	
	public ArrayList<WindowDisplay> displays = new ArrayList<WindowDisplay>();
	
	public boolean overridePickBlock = false;
	public HitResult trueGameHitResult = null;
	
	public WLCToplevel pinnedToplevel = null;
	
	public WindowItemManager itemManager = new WindowItemManager();
	public XDGDesktopManager xdgManager;
	
	public KeyMapping keyOpenScreen;
	public KeyMapping keyOpenAppLauncher;
	public KeyMapping keyCaptureKeyboard;
	/** Toggle player-follow lock on the targeted world window. */
	public KeyMapping keyFollowWindow;
	/** Start exclusive window placement grab (same as Window Manager "Grab"). */
	public KeyMapping keyGrabWindow;
	
	public WindowInHandRenderer windowInHandRenderer = new WindowInHandRenderer();
	public WindowInItemFrameRenderer windowInItemFrameRenderer = new WindowInItemFrameRenderer();
	public WaylandHudRenderer hudRenderer = new WaylandHudRenderer(this);
	
	public PointerGrabMap pointerGrabs = new PointerGrabMap(this);
	
	// HitResult of currently hovered WindowDisplay
	// Only non-null, when no exclusive pointer grabs are currently active
	public DisplayHitResult hoveredDisplay = null;
	
	/** Shipped keyboard capture state machine (soft/hard). */
	public final KeyboardCapture keyboardCapture = new KeyboardCapture();
	
	/** Bridge adapter used by {@link #keyboardCapture}. */
	private final KeyboardCapture.Target keyboardCaptureTarget = new KeyboardCapture.Target() {
		@Override
		public void activateKeyboard() {
			if(bridge != null) bridge.activateKeyboard();
		}
		
		@Override
		public void deactivateKeyboard() {
			if(bridge != null) bridge.deactivateKeyboard();
		}
		
		@Override
		public void pressKey(int scancode) {
			if(bridge != null) bridge.pressKey(scancode);
		}
		
		@Override
		public void releaseKey(int scancode) {
			if(bridge != null) bridge.releaseKey(scancode);
		}
	};
	
	/**
	 * Optional override of the keyboard target for unit tests that drive
	 * {@link #onKeyPress} without a live native bridge.
	 */
	KeyboardCapture.Target testKeyboardTarget = null;
	
	private KeyboardCapture.Target keyboardTarget() {
		return testKeyboardTarget != null ? testKeyboardTarget : keyboardCaptureTarget;
	}
	
	/** @deprecated use {@link #keyboardCapture}{@code .mode()} */
	@Deprecated
	public KeyboardCaptureMode getKeyboardCaptureMode() {
		return switch(keyboardCapture.mode()) {
			case NONE -> KeyboardCaptureMode.NONE;
			case CAPTURE -> KeyboardCaptureMode.CAPTURE;
			case HARD_CAPTURE -> KeyboardCaptureMode.HARD_CAPTURE;
		};
	}
	
	public PointerCapture pointerCapture = null;
	
	private boolean playerUsingWindowItem = false;
	private boolean playerWasUsingWindowItem = false;
	
	public @Nullable CursorShape cursorShape = null;
	
	@Override
	public void onInitializeClient() {
		WaylandCraftCommon.LOGGER.info("Initializing WaylandCraft");
		
		instance = this;
		
		// Grab owns default B (objective). Window manager moved off B so both binds work.
		keyGrabWindow = KeyMappingHelper.registerKeyMapping(new KeyMapping("waylandcraft.key.grabWindow", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, KEYBIND_CATEGORY));
		keyOpenScreen = KeyMappingHelper.registerKeyMapping(new KeyMapping("waylandcraft.key.windowManager", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, KEYBIND_CATEGORY));
		keyOpenAppLauncher = KeyMappingHelper.registerKeyMapping(new KeyMapping("waylandcraft.key.appLauncher", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, KEYBIND_CATEGORY));
		keyCaptureKeyboard = KeyMappingHelper.registerKeyMapping(new KeyMapping("waylandcraft.key.captureKeyboard", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, KEYBIND_CATEGORY));
		// Y — unused by sibling binds (B/N/V/G); rebindable in Controls.
		keyFollowWindow = KeyMappingHelper.registerKeyMapping(new KeyMapping("waylandcraft.key.followWindow", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, KEYBIND_CATEGORY));
		
		WindowItemModel.register();
		
		settingsManager = new WaylandCraftSettingsManager(this);
		
		if(Platform.get() != Platform.LINUX) {
			WaylandCraftCommon.LOGGER.error("Invalid platform detected! Most mod features will be disabled");
			WaylandCraft.fallbackMode = true;
			return;
		}
		
		LevelRenderEvents.COLLECT_SUBMITS.register(this::renderWorld);
		LevelRenderEvents.END_EXTRACTION.register(this::updateWorld);
		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
		ClientPlayConnectionEvents.JOIN.register(this::onClientJoin);
		ClientPlayConnectionEvents.DISCONNECT.register(this::onClientDisconnect);
		ItemTooltipCallback.EVENT.register(this::addWindowItemTooltip);
		ClientTickEvents.START_CLIENT_TICK.register(itemManager);
		
		WaylandCraftCommon.instance.windowItemInteractionProvider = itemManager;
		
		hudRenderer.register();
	}
	
	/* Update bridge and clients. May be called at any state of the game, even outside of a level
	 * Called after game render in Minecraft::runTick
	 */
	public void update() {
		if(fallbackMode) return;
		
		if(bridge == null) {
			bridge = WaylandCraftBridge.start();
			waylandSocket = bridge.getSocket();
			x11Display = bridge.getX11Display();
			xdgManager = new XDGDesktopManager(this);
			registerSettingsResponders();
			settingsManager.loadKeymap();
			
			WaylandCraftCommon.LOGGER.info("Wayland server started on " + waylandSocket);
			WaylandCraftCommon.LOGGER.info("Xwayland started on " + x11Display);
		}
		bridge.update();
	}
	
	private void registerSettingsResponders() {
		settingsManager.registerResponder(WaylandCraftSettings.TERMINAL_CHOICE, (value) -> {
			bridge.setPreferredTerminal((String) value);
		});
		settingsManager.registerResponder(WaylandCraftSettings.OUTPUT_SCALE, (value) -> {
			// setIntSetting already clamps; apply effective scale to the compositor.
			bridge.setOutputScale(IntegerScale.clamp((Integer) value));
		});
	}
	
	public void renderWorld(LevelRenderContext ctx) {
		if(bridge == null) return;
		
		// Per-frame follow with interpolated eye position (partial ticks) so
		// locked windows track smoothly between client ticks — applied before
		// submit so this frame's draw uses the current pose.
		Minecraft minecraft = Minecraft.getInstance();
		if(minecraft.player != null) {
			applyFollowLocks(minecraft);
		}
		
		displays.forEach((d) -> d.render(ctx));
	}
	
	public void updateWorld(LevelExtractionContext ctx) {
		for(WLCPopup popup : bridge.getMappedPopups()) {
			WLCAbstractWindow root = popup;
			while((root = ((WLCPopup) root).getParent()) instanceof WLCPopup);
			
			WLCToplevel toplevel = (WLCToplevel) root;
			boolean toplevelHasWindow = hasDisplayFor(toplevel);
			boolean popupHasWindow = hasDisplayFor(popup);
			if(toplevelHasWindow && !popupHasWindow) {
				getOrCreateDisplay(popup);
			}
			else if(!toplevelHasWindow && popupHasWindow) {
				displays.removeIf((w) -> w.window == popup);
			}
		}
		
		displays.removeIf((d) -> !d.isValid());
		displays.forEach((d) -> d.updateGeometry());
		
		for(WLCPopup popup : bridge.getMappedPopups()) {
			anchorToParent(popup);
		}
		
		updateDisplayRequests();
		
		itemManager.giveItemsIfMissing(bridge.getNewToplevels());
		
		// Also keep locks clean and poses current on the world-update path
		// (invalid displays drop out; pose stays ready between submits).
		Minecraft minecraft = Minecraft.getInstance();
		if(minecraft.player != null) {
			applyFollowLocks(minecraft);
		}
		
		boolean inWMScreen = Minecraft.getInstance().gui.screen() instanceof WindowManagerScreen;
		
		// Make sure the toplevels are focused in their respective order and being refocused when a toplevel disappears
		if(!inWMScreen) {
			WLCToplevel focus = bridge.getMostToLeastRecentFocus()
					.filter((t) -> hasDisplayFor(t))
					.findFirst()
					.orElse(null);
			
			bridge.focusSurface(focus);
		}
		
		Camera camera = ctx.camera();
		processPointerMotion(camera);
		
		if(Minecraft.getInstance().player == null || !Minecraft.getInstance().player.isUsingItem()) playerUsingWindowItem = false;
		if(playerUsingWindowItem) {
			ItemStack item = Minecraft.getInstance().player.getUseItem();
			if(item.is(WindowItem.WINDOW)) {
				WLCToplevel toplevel = getToplevel(item);
				
				if(toplevel != null) {
					WindowDisplay display = getOrCreateDisplay(toplevel);
					if(!playerWasUsingWindowItem) {
						display.anchorDistance = 2.0;
					}
					
					display.doGrabMove(camera.position(), new Vec3(camera.forwardVector()), new Vec3(camera.upVector()), camera.yRot());
					
					WaylandCraft.instance.bridge.focusSurface(toplevel);
				}
			}
			else playerUsingWindowItem = false;
		}
		playerWasUsingWindowItem = playerUsingWindowItem;
		
		updateOutputSize(inWMScreen);
	}
	
	public void startUsingWindowItem() {
		playerUsingWindowItem = true;
	}
	
	public void enableKeyboardCapture(boolean hardCapture) {
		keyboardCapture.enable(hardCapture, keyboardTarget());
	}
	
	public void disableKeyboardCapture() {
		if(!keyboardCapture.isActive()) return;
		keyboardCapture.disable(keyboardTarget());
		disablePointerCapture();
	}
	
	public void onClientTick(Minecraft minecraft) {
		if(minecraft.player == null) return;
		checkKeybinds(minecraft);
	}
		
	private void checkKeybinds(Minecraft minecraft) {
		if(keyOpenScreen.consumeClick()) {
			if(keyboardCapture.isActive()) {
				keyboardCapture.disable(keyboardTarget());
			}
			pointerGrabs.releaseAll();
			minecraft.gui.setScreen(new WindowManagerScreen(WaylandCraft.instance));
		}
		else if(keyOpenAppLauncher.consumeClick()) {
			minecraft.gui.setScreen(new AppLauncherScreen(WaylandCraft.instance));
		}
		else if(keyCaptureKeyboard.consumeClick()) {
			enableKeyboardCapture(false);
		}
		else if(keyFollowWindow != null && keyFollowWindow.consumeClick()) {
			toggleFollowLock(minecraft);
		}
		else if(keyGrabWindow != null && keyGrabWindow.consumeClick()) {
			startWindowGrab(minecraft);
		}
	}
	
	/**
	 * Grab hotkey: start exclusive placement grab on the world window the player
	 * is looking at — the same target that currently receives mouse input
	 * ({@link #hoveredDisplay}). If nothing is looked at (or out of range / not
	 * mouse-eligible), this is a no-op. Does <em>not</em> fall back to most-recent
	 * focus. Window Manager screen "Grab" still uses its selected/focused toplevel.
	 */
	void startWindowGrab(Minecraft minecraft) {
		applyGrabHotkey(hoveredDisplay);
	}
	
	/**
	 * Resolve the look-at / mouse-hover window for the grab hotkey.
	 * Uses the same eligibility standard as pointer input: only a non-null
	 * {@link #hoveredDisplay} (in-range, not occluded, interaction-eligible).
	 *
	 * @return the hovered display, or null if the hotkey should no-op
	 */
	static @Nullable WindowDisplay resolveGrabHotkeyTarget(@Nullable DisplayHitResult hovered) {
		if(hovered == null) return null;
		return hovered.target;
	}
	
	/**
	 * Core grab-hotkey action used by {@link #startWindowGrab}. Package-visible
	 * so unit tests can drive synthetic hover without a live client.
	 *
	 * @return the display that was grabbed, or null if no-op
	 */
	@Nullable WindowDisplay applyGrabHotkey(@Nullable DisplayHitResult hover) {
		WindowDisplay target = resolveGrabHotkeyTarget(hover);
		if(target == null) return null;
		startExclusiveWindowGrab(target);
		return target;
	}
	
	/**
	 * Start exclusive {@link WindowGrab} on a world display (same machinery as
	 * the WM Grab button uses for a resolved display). Package-visible for tests.
	 */
	void startExclusiveWindowGrab(WindowDisplay target) {
		pointerGrabs.startExclusive(new WindowGrab(target, 0));
	}
	
	/**
	 * Toggle player-follow lock on the currently targeted world window.
	 * Press again on a locked window to unlock; with no target, unlock all
	 * so the lock is always reachable.
	 */
	void toggleFollowLock(Minecraft minecraft) {
		if(minecraft.player == null) return;
		if(hoveredDisplay != null) {
			WindowDisplay target = hoveredDisplay.target;
			if(target.isFollowLocked()) {
				target.clearFollowLock();
			}
			else {
				LocalPlayer player = minecraft.player;
				Vec3 pos = WaylandCraftUtils.getPosition(player);
				Vec3 look = WaylandCraftUtils.getLookVector(player);
				Vec3 up = WaylandCraftUtils.getUpVector(player);
				target.lockFollow(pos, look, up);
			}
			return;
		}
		// No hover: clear every follow lock so unlock stays reachable.
		for(WindowDisplay display : displays) {
			display.clearFollowLock();
		}
	}
	
	/**
	 * Reposition every follow-locked world window using a world-fixed offset from
	 * the (interpolated) player eye. Invalid windows drop out of the lock cleanly.
	 * Intended for the per-frame {@link #renderWorld} / {@link #updateWorld} path
	 * so motion is smooth between client ticks.
	 */
	void applyFollowLocks(Minecraft minecraft) {
		if(minecraft.player == null) return;
		LocalPlayer player = minecraft.player;
		// Interpolated eye position (partial ticks) for continuous motion between ticks.
		Vec3 pos = WaylandCraftUtils.getPosition(player);
		for(WindowDisplay display : displays) {
			if(!display.isValid()) {
				display.clearFollowLock();
				continue;
			}
			display.applyFollowIfLocked(pos);
		}
	}
	
	private void onClientJoin(ClientPacketListener listener, PacketSender sender, Minecraft minecraft) {
		minecraft.gui.chatListener().handleSystemMessage(Component.literal("Wayland compositor running on " + waylandSocket), false);
		if(x11Display != null) minecraft.gui.chatListener().handleSystemMessage(Component.literal("xwayland-satellite running on " + x11Display), false);
		itemManager.giveItemsIfMissing(bridge.getMappedToplevels());
	}
	
	private void onClientDisconnect(ClientPacketListener listener, Minecraft minecraft) {
		displays.clear();
		itemManager.reset();
	}
	
	@Nullable
	public static WLCToplevel getToplevel(ItemStack item) {
		if(item == null) return null;
		if(WaylandCraft.instance.bridge == null) return null;
		
		WindowHandle data = item.get(WindowItem.WINDOW_HANDLE);
		if(data == null) return null;
		if(!data.matchesPlayer(Minecraft.getInstance().player)) return null;
		
		return WaylandCraft.instance.bridge.getToplevel(data.handle());
	}
	
	private void addWindowItemTooltip(ItemStack itemStack, TooltipContext ctx, TooltipFlag flag, List<Component> list) {
		WindowHandle handle = itemStack.get(WindowItem.WINDOW_HANDLE);
		if(handle != null) {
			String text = "Handle 0x" + Long.toHexString(handle.handle());
			Component component = Component
					.literal(text)
					.withStyle(ChatFormatting.GRAY);
			list.add(component);
			String owner = "Owner " + handle.player();
			component = Component
					.literal(owner)
					.withStyle(ChatFormatting.GRAY);
			list.add(component);
		}
	}
	
	private void updateDisplayRequests() {
		// Hide all windows that were minimized and unset minimize requested state
		displays.removeIf((w) -> w.window instanceof WLCToplevel && ((WLCToplevel) w.window).requests.minimize);
		Stream.of(bridge.getToplevels()).forEach((t) -> t.requests.minimize = false);
		
		// Handle any maximize or unmaximize requests
		for(WLCToplevel toplevel : bridge.getMappedToplevels()) {
			if(toplevel.requests.maximize && toplevel.requests.unmaximize) {
				// Both requests shouldn't happen at the same time
				toplevel.restoreGeometry = null;
			}
			else if(toplevel.requests.maximize) {
				// Maximize toplevel and store its old geometry
				toplevel.restoreGeometry = toplevel.geometry;
				bridge.maximizeToplevel(toplevel);
			}
			else if(toplevel.requests.unmaximize) {
				// Unmaximize toplevel and attempt to restore old geometry
				SurfaceGeometry newGeometry = toplevel.restoreGeometry;
				if(newGeometry == null) newGeometry = toplevel.geometry;
				
				// resizeToplevel also unsets the maximize flag
				bridge.resizeToplevel(toplevel, newGeometry.width(), newGeometry.height());
				toplevel.restoreGeometry = null;
			}
			
			toplevel.requests.maximize = toplevel.requests.unmaximize = false;
		}
		
		// Handle any fullscreen or unfullscreen requests
		for(WLCToplevel toplevel : bridge.getToplevels()) {
			if(toplevel.requests.fullscreen && toplevel.requests.unfullscreen) {
				// Both requests shouldn't happen at the same time
				toplevel.restoreGeometry = null;
			}
			else if(toplevel.requests.fullscreen) {
				// Fullscreen toplevel and store its old geometry
				toplevel.restoreGeometry = toplevel.geometry;
				bridge.fullscreenToplevel(toplevel);
			}
			else if(toplevel.requests.unfullscreen) {
				// Unfullscreen toplevel and attempt to restore old geometry
				SurfaceGeometry newGeometry = toplevel.restoreGeometry;
				if(newGeometry == null) newGeometry = toplevel.geometry;
				
				// resizeToplevel also unsets the fullscreen flag
				bridge.resizeToplevel(toplevel, newGeometry.width(), newGeometry.height());
				toplevel.restoreGeometry = null;
			}
			
			toplevel.requests.fullscreen = toplevel.requests.unfullscreen = false;
		}
		
		Integer moveRequest = bridge.checkMoveRequest();
		if(moveRequest != null) {
			ImplicitGrab implicit = pointerGrabs.dropImplicitMatching(moveRequest.intValue());
			if(implicit != null) {
				// The serial matched an active implicit grab
				pointerGrabs.startExclusive(new MoveGrab(implicit));
			}
		}
		
		ResizeRequest resizeRequest = bridge.checkResizeRequest();
		if(resizeRequest != null) {
			ImplicitGrab implicit = pointerGrabs.dropImplicitMatching(resizeRequest.serial());
			if(implicit != null) {
				// The serial matched an active implicit grab
				pointerGrabs.startExclusive(new ResizeGrab(implicit, resizeRequest.edges()));
			}
		}
		
		Integer dndRequest = bridge.checkDndRequest();
		if(dndRequest != null) {
			ImplicitGrab implicit = pointerGrabs.dropImplicitMatching(dndRequest);
			if(implicit != null) {
				WaylandCraftCommon.LOGGER.info("DND STARTED");
				// The serial matched an active implicit grab
				pointerGrabs.startExclusive(new DNDGrab(implicit));
			}
			else {
				// Couldn't match implicit grab, have to cancel dnd
				WaylandCraftCommon.LOGGER.info("drag and drop did not match implicit grab");
				bridge.dndCancel();
			}
		}
	}
	
	private void updateOutputSize(boolean inWMScreen) {
		int outputWidth = Minecraft.getInstance().getWindow().getWidth();
		int outputHeight = Minecraft.getInstance().getWindow().getHeight();
		
		Size size = bridge.getOutputSize();
		if(size.width() != outputWidth || size.height() != outputHeight) {
			bridge.resizeOutput(outputWidth, outputHeight);
			if(!inWMScreen) bridge.setOutputBounds(outputWidth, outputHeight);
		}
	}
	
	public @Nullable WindowDisplay getDisplay(WLCAbstractWindow window) {
		return displays.stream().filter((w) -> w.window == window).findAny().orElse(null);
	}
	
	public WindowDisplay getOrCreateDisplay(WLCAbstractWindow window) {
		WindowDisplay display = getDisplay(window);
		if(display != null) return display;
		
		display = new WindowDisplay(window);
		displays.add(display);
		
		return display;
	}
	
	public boolean hasDisplayFor(WLCAbstractWindow window) {
		return getDisplay(window) != null;
	}
	
	public void disablePointerCapture() {
		if(pointerCapture == null) return;
		bridge.unlockPointer();
		pointerCapture = null;
	}
	
	private void processPointerMotion(Camera camera) {
		this.cursorShape = null;
		
		if(pointerCapture != null) {
			if(!pointerCapture.surface.isAlive()) {
				pointerCapture = null;
				return;
			}
			
			this.cursorShape = bridge.getCursorShape();
			
			if(!bridge.maybeLockPointer(pointerCapture.surface)) {
				disablePointerCapture();
			}
			
			return;
		}
		
		// Reset hovered display and pick block override
		this.hoveredDisplay = null;
		this.overridePickBlock = false;
		
		if(Minecraft.getInstance().gui.screen() instanceof WindowManagerScreen) {
			return;
		}
		else if(Minecraft.getInstance().gui.screen() != null) {
			pointerGrabs.releaseAll();
			bridge.sendMotionOutside();
			return;
		}
		
		Vec3 pos = camera.position();
		Vec3 look = new Vec3(camera.forwardVector());
		Vec3 up = new Vec3(camera.upVector());
		
		DisplayHitResult finalHitResult = null;
		double finalDistance = Double.POSITIVE_INFINITY;
		for(WindowDisplay display : displays) {
			DisplayHitResult hit = display.intersect(pos, look);
			if(hit == null || hit.isMiss()) continue;
			
			double dist = hit.position.distanceToSqr(pos);
			if(finalHitResult == null || dist < finalDistance) {
				finalHitResult = hit;
				finalDistance = dist;
			}
		}
		
		// Check if game hit result closer
		// Must use trueGameHitResult because the game hit result is overridden by overridePickBlock
		HitResult gameHitResult = trueGameHitResult;
		double gameHitDistance = (gameHitResult == null || gameHitResult.getType() == HitResult.Type.MISS) ? Double.POSITIVE_INFINITY : gameHitResult.getLocation().distanceToSqr(pos);
		if(gameHitDistance < finalDistance) finalHitResult = null;
		
		// Check for player reach
		if(finalHitResult != null && !finalHitResult.position.closerThan(pos, Minecraft.getInstance().player.blockInteractionRange())) finalHitResult = null;
		
		if(!pointerGrabs.isExclusiveGrabActive()) hoveredDisplay = finalHitResult;
		
		// Check for pointer grab and short-circuit if any
		if(pointerGrabs.isGrabActive()) {
			this.overridePickBlock = true;
			this.cursorShape = bridge.getCursorShape();
			
			pointerGrabs.moveWorld(pos, look, up, camera.yRot(), camera.xRot());
			if(finalHitResult != null) {
				pointerGrabs.hover(finalHitResult.target.window, finalHitResult.surface, finalHitResult.surfaceLocalRelative.x, finalHitResult.surfaceLocalRelative.y);
			}
			else {
				pointerGrabs.hoverNone();
			}
			
			return;
		}
		
		/* All of the following code will only be executed when there aren't any active pointer grabs */
		
		if(hoveredDisplay != null && !canStartInteracting()) hoveredDisplay = null;
		
		if(hoveredDisplay != null) {
			this.overridePickBlock = true;
		}
		
		if(hoveredDisplay != null && hoveredDisplay.dist >= 0) {
			WLCSurface surface = hoveredDisplay.surface;
			Vec3 rel = hoveredDisplay.surfaceLocalRelative;
			
			this.cursorShape = bridge.getCursorShape();
			bridge.sendMotionRefocus(surface, rel.x, rel.y);
			
			if(keyboardCapture.isActive() && bridge.maybeLockPointer(surface)) {
				pointerCapture = new PointerCapture(surface, rel.x, rel.y);
			}
			
			// Focus on hover
			if(settings.getFocusOnHover() && hoveredDisplay.target.window instanceof WLCToplevel toplevel) {
				bridge.focusSurface(toplevel);
			}
		}
		else {
			bridge.sendMotionOutside();
		}
	}
	
	/* Handle mouse button input
	 * Returns true when the mouse button action has been consumed
	 */
	public boolean onButtonPress(long windowHandle, int button, int action, int modifiers) {
		if(bridge == null) return false;
		
		if(pointerCapture != null) {
			if(action == 1 && !pointerCapture.pressedButtons.contains(button)) {
				bridge.sendButton(0x110 + button, 1);
				pointerCapture.pressedButtons.add(button);
			}
			else if(action == 0 && pointerCapture.pressedButtons.contains(button)) {
				bridge.sendButton(0x110 + button, 0);
				pointerCapture.pressedButtons.remove(button);
			}
			else if(action == 0) {
				// Forward release to minecraft if it wasn't part of this pointer capture
				return false;
			}
			return true;
		}
		
		if(action == 0 && pointerGrabs.isGrabActive(button)) {
			pointerGrabs.release(button);
			return true;
		}
		
		if(pointerGrabs.isExclusiveGrabActive()) return true;
		
		// Handle implicit pointer grab button presses
		if(action == 1) {
			// Start new implicit grab when conditions are met
			if(!pointerGrabs.isImplicitActive() && hoveredDisplay != null && hoveredDisplay.dist >= 0) {
				pointerGrabs.startImplicit(hoveredDisplay);
				WLCAbstractWindow window = hoveredDisplay.target.window;
				if(window instanceof WLCToplevel) bridge.focusSurface((WLCToplevel) window);
			}
			
			// If an implicit pointer grab is now active, capture the button press
			if(pointerGrabs.isImplicitActive()) {
				pointerGrabs.sendImplicitButton(button);
				return true;
			}
			
			// If clicking on a window at all, the button press should be captured, even if it wasn't passed on to the application
			if(hoveredDisplay != null) return true;
		}
		
		return false;
	}
	
	private boolean canStartInteracting() {
		LocalPlayer player = Minecraft.getInstance().player;
		if(player == null) return false;
		if(player.isUsingItem()) return false;
		return true;
	}
	
	/* Handle mouse being turned in game
	 * Returns true when the mouse move has been consumed
	 */
	public boolean onMouseTurn(double dx, double dy) {
		if(bridge == null) return false;
		if(pointerCapture == null) return false;
		
		bridge.sendRelativeMotion(dx, dy);
		return true;
	}
	
	/* Handle mouse scroll input
	 * Returns true when the mouse scroll action has been consumed
	 */
	public boolean onScroll(long windowHandle, double scrollX, double scrollY) {
		if(bridge == null) return false;
		
		if(playerUsingWindowItem) {
			WLCToplevel toplevel = getToplevel(Minecraft.getInstance().player.getUseItem());
			if(toplevel != null) {
				WindowDisplay display = getDisplay(toplevel);
				if(display != null) {
					display.adjustAnchorDistance(scrollY);
					return true;
				}
			}
		}

		if(pointerGrabs.isExclusiveGrabActive()) {
			pointerGrabs.onScroll(scrollX, scrollY);
			return true;
		}
		
		if(hoveredDisplay != null) {
			if(hoveredDisplay.dist < 0) return true;
			
			bridge.sendScroll(0, -scrollY);
			bridge.sendScroll(1, -scrollX);
			
			WLCAbstractWindow window = hoveredDisplay.target.window;
			if(window instanceof WLCToplevel) bridge.focusSurface((WLCToplevel) window);
			
			return true;
		}
		
		return false;
	}
	
	/* Handle keyboard input
	 * Returns true when the key press action has been consumed
	 * This code just completely naively assumes that the scancode received by GLFW
	 * is also the correct matching Wayland scancode for the default XKBConfig.
	 * For X11 and Wayland hosts, this is a huge hack but should mostly work for now
	 */
	public boolean onKeyPress(long windowHandle, int key, int scancode, int action, int modifiers) {
		// Production requires a live bridge; tests may supply testKeyboardTarget alone.
		if(bridge == null && testKeyboardTarget == null) return false;
		
		KeyboardCapture.Mode before = keyboardCapture.mode();
		boolean consumed = keyboardCapture.onKeyPress(key, scancode, action, modifiers, keyboardTarget());
		// Soft/hard capture disable also releases pointer capture
		if(before != KeyboardCapture.Mode.NONE && keyboardCapture.mode() == KeyboardCapture.Mode.NONE) {
			disablePointerCapture();
		}
		return consumed;
	}
	
	public static int correctScancode(int scancode) {
		if(GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) {
			scancode += 8;
		}
		return scancode;
	}
	
	private void anchorToParent(WLCPopup popup) {
		WindowDisplay window = displays.stream().filter((w) -> w.window == popup).findAny().orElse(null);
		WindowDisplay parent = displays.stream().filter((w) -> w.window == popup.getParent()).findAny().orElse(null);
		
		if(window == null || parent == null) return;
		
		// If the parent is also a popup, first make it anchor itself
		if(parent.window instanceof WLCPopup) {
			anchorToParent((WLCPopup) parent.window);
		}
		
		window.rotate(parent.normal(), parent.down());
		window.moveOrigin(parent.localToWorld(popup.offsetX, popup.offsetY, 0.01));
	}
	
	public static enum KeyboardCaptureMode {
		
		NONE, CAPTURE, HARD_CAPTURE;
		
	}
	
	public static class PointerCapture {
		
		public final WLCSurface surface;
		
		// Pointer capture entry surface-local coordinates
		public double x;
		public double y;
		
		public HashSet<Integer> pressedButtons = new HashSet<Integer>();
		
		public PointerCapture(WLCSurface surface, double x, double y) {
			this.surface = surface;
			this.x = x;
			this.y = y;
		}
		
	}
	
}

