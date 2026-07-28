package dev.evvie.waylandcraft;

import org.lwjgl.glfw.GLFW;

/**
 * Soft/hard keyboard capture state machine used while Wayland windows should
 * receive keys instead of Minecraft player controls.
 *
 * <p>This is the shipped decision path for capture enable/disable and key
 * claim/forwarding. {@link WaylandCraft} wires it to the live bridge and
 * mixin cancel site.
 */
public final class KeyboardCapture {
	
	public enum Mode {
		NONE,
		CAPTURE,
		HARD_CAPTURE;
	}
	
	/**
	 * Sink for compositor keyboard actions. Production uses
	 * {@link dev.evvie.waylandcraft.bridge.WaylandCraftBridge}; tests use a recorder.
	 */
	public interface Target {
		void activateKeyboard();
		void deactivateKeyboard();
		void pressKey(int scancode);
		void releaseKey(int scancode);
	}
	
	private Mode mode = Mode.NONE;
	
	public Mode mode() {
		return mode;
	}
	
	public boolean isActive() {
		return mode != Mode.NONE;
	}
	
	public void enable(boolean hardCapture, Target target) {
		if(mode != Mode.NONE) return;
		mode = hardCapture ? Mode.HARD_CAPTURE : Mode.CAPTURE;
		target.activateKeyboard();
	}
	
	public void disable(Target target) {
		if(mode == Mode.NONE) return;
		mode = Mode.NONE;
		target.deactivateKeyboard();
	}
	
	/**
	 * Handle a key event while in-game (no GUI screen).
	 *
	 * @return {@code true} if the event is consumed and vanilla Minecraft must
	 *         not process it (movement, inventory, pause menu, etc.)
	 */
	public boolean onKeyPress(int key, int scancode, int action, int modifiers, Target target) {
		// ALT+Q toggles hard capture on/off (consumes both press and release)
		if(key == GLFW.GLFW_KEY_Q && modifiers == GLFW.GLFW_MOD_ALT) {
			if(action == GLFW.GLFW_RELEASE) return true;
			
			if(mode != Mode.HARD_CAPTURE) {
				enable(true, target);
			}
			else {
				disable(target);
			}
			return true;
		}
		
		if(mode == Mode.NONE) return false;
		
		// Soft capture exits on ESC; hard capture does not
		if(mode == Mode.CAPTURE && key == GLFW.GLFW_KEY_ESCAPE) {
			disable(target);
			return true;
		}
		
		if(action == GLFW.GLFW_PRESS) {
			target.pressKey(scancode);
		}
		else if(action == GLFW.GLFW_RELEASE) {
			target.releaseKey(scancode);
		}
		
		return true;
	}
	
}
