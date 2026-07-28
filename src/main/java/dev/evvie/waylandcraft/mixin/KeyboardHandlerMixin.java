package dev.evvie.waylandcraft.mixin;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.evvie.waylandcraft.WaylandCraft;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

/**
 * Intercepts Minecraft keyboard handling so soft/hard keyboard capture can
 * claim keys before vanilla applies them (KeyMapping / pauseGame / movement).
 *
 * <p>Must inject at HEAD (cancellable). In 26.2, {@code InputConstants.getKey}
 * ordinal 1 is only on the open-screen path; ordinal 2 is the in-game path.
 * A mid-method inject at ordinal 1 never runs when no GUI screen is open, which
 * left capture showing the HUD notice while keys still controlled the player.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {
	
	/**
	 * Track host keys for the compositor (modifiers, etc.) and cancel vanilla
	 * handling when capture consumes the event. Cancel runs before
	 * {@code KeyMapping.set} and {@code Minecraft.pauseGame}.
	 */
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	public void onKeyPressHead(long windowHandle, int action, KeyEvent event, CallbackInfo info) {
		if(WaylandCraft.instance == null || WaylandCraft.instance.bridge == null) return;
		
		int scancode = WaylandCraft.correctScancode(event.scancode());
		
		if(action == GLFW.GLFW_PRESS || action == GLFW.GLFW_RELEASE) {
			WaylandCraft.instance.bridge.internalKeyUpdate(scancode, action == GLFW.GLFW_PRESS);
		}
		
		// Only claim in-game keys (no level / open screen → leave vanilla alone)
		if(Minecraft.getInstance().level == null) return;
		if(Minecraft.getInstance().gui.screen() != null) return;
		
		if(WaylandCraft.instance.onKeyPress(windowHandle, event.key(), scancode, action, event.modifiers())) {
			info.cancel();
		}
	}
	
}
