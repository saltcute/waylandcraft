package dev.evvie.waylandcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * Drives the shipped {@link KeyboardCapture} decision path (used by
 * {@link WaylandCraft#onKeyPress}) with a recording bridge target.
 */
public class KeyboardCaptureTest {
	
	private KeyboardCapture capture;
	private RecordingTarget target;
	
	@BeforeEach
	void setUp() {
		capture = new KeyboardCapture();
		target = new RecordingTarget();
	}
	
	@Test
	void captureOffDoesNotClaimOrdinaryKeys() {
		assertFalse(capture.onKeyPress(GLFW.GLFW_KEY_W, 17, GLFW.GLFW_PRESS, 0, target));
		assertFalse(capture.onKeyPress(GLFW.GLFW_KEY_W, 17, GLFW.GLFW_RELEASE, 0, target));
		assertEquals(KeyboardCapture.Mode.NONE, capture.mode());
		assertTrue(target.calls.isEmpty(), "bridge must not receive keys when capture is off: " + target.calls);
	}
	
	@Test
	void softCaptureClaimsKeysAndForwardsPressRelease() {
		capture.enable(false, target);
		assertEquals(KeyboardCapture.Mode.CAPTURE, capture.mode());
		assertEquals(List.of("activate"), target.calls);
		target.calls.clear();
		
		assertTrue(capture.onKeyPress(GLFW.GLFW_KEY_A, 30, GLFW.GLFW_PRESS, 0, target));
		assertTrue(capture.onKeyPress(GLFW.GLFW_KEY_A, 30, GLFW.GLFW_RELEASE, 0, target));
		
		assertEquals(List.of("press:30", "release:30"), target.calls);
		assertEquals(KeyboardCapture.Mode.CAPTURE, capture.mode());
	}
	
	@Test
	void softCaptureEscapeExitsAndClaimsEvent() {
		capture.enable(false, target);
		target.calls.clear();
		
		assertTrue(capture.onKeyPress(GLFW.GLFW_KEY_ESCAPE, 1, GLFW.GLFW_PRESS, 0, target));
		assertEquals(KeyboardCapture.Mode.NONE, capture.mode());
		assertEquals(List.of("deactivate"), target.calls);
		// Escape must not be forwarded as a compositor key when exiting soft capture
		assertFalse(target.calls.stream().anyMatch(c -> c.startsWith("press:") || c.startsWith("release:")));
	}
	
	@Test
	void hardCaptureEscapeDoesNotExit() {
		capture.enable(true, target);
		target.calls.clear();
		
		assertTrue(capture.onKeyPress(GLFW.GLFW_KEY_ESCAPE, 1, GLFW.GLFW_PRESS, 0, target));
		assertEquals(KeyboardCapture.Mode.HARD_CAPTURE, capture.mode());
		// ESC is forwarded to the compositor under hard capture
		assertEquals(List.of("press:1"), target.calls);
	}
	
	@Test
	void hardCaptureAltQTogglesOff() {
		capture.enable(true, target);
		target.calls.clear();
		
		// press ALT+Q exits hard capture
		assertTrue(capture.onKeyPress(GLFW.GLFW_KEY_Q, 16, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_ALT, target));
		assertEquals(KeyboardCapture.Mode.NONE, capture.mode());
		assertEquals(List.of("deactivate"), target.calls);
		
		// release is still claimed so vanilla does not see the Q
		assertTrue(capture.onKeyPress(GLFW.GLFW_KEY_Q, 16, GLFW.GLFW_RELEASE, GLFW.GLFW_MOD_ALT, target));
		assertEquals(KeyboardCapture.Mode.NONE, capture.mode());
	}
	
	@Test
	void altQEnablesHardCaptureFromNone() {
		assertTrue(capture.onKeyPress(GLFW.GLFW_KEY_Q, 16, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_ALT, target));
		assertEquals(KeyboardCapture.Mode.HARD_CAPTURE, capture.mode());
		assertEquals(List.of("activate"), target.calls);
	}
	
	@Test
	void waylandCraftOnKeyPressEntryPointClaimsAndForwards() {
		WaylandCraft craft = new WaylandCraft();
		RecordingTarget recording = new RecordingTarget();
		craft.testKeyboardTarget = recording;
		
		// Public enable API
		craft.enableKeyboardCapture(false);
		assertEquals(KeyboardCapture.Mode.CAPTURE, craft.keyboardCapture.mode());
		assertTrue(recording.calls.contains("activate"));
		recording.calls.clear();
		
		// Real shipped entry point used by KeyboardHandlerMixin
		assertTrue(craft.onKeyPress(0L, GLFW.GLFW_KEY_D, 32, GLFW.GLFW_PRESS, 0));
		assertTrue(craft.onKeyPress(0L, GLFW.GLFW_KEY_D, 32, GLFW.GLFW_RELEASE, 0));
		assertEquals(List.of("press:32", "release:32"), recording.calls);
		
		// Soft ESC via real entry point
		recording.calls.clear();
		assertTrue(craft.onKeyPress(0L, GLFW.GLFW_KEY_ESCAPE, 1, GLFW.GLFW_PRESS, 0));
		assertEquals(KeyboardCapture.Mode.NONE, craft.keyboardCapture.mode());
		assertEquals(List.of("deactivate"), recording.calls);
		
		// Capture off: ordinary keys not claimed
		assertFalse(craft.onKeyPress(0L, GLFW.GLFW_KEY_W, 17, GLFW.GLFW_PRESS, 0));
	}
	
	/** Records activate/deactivate/press/release in order. */
	static final class RecordingTarget implements KeyboardCapture.Target {
		final List<String> calls = new ArrayList<>();
		
		@Override
		public void activateKeyboard() {
			calls.add("activate");
		}
		
		@Override
		public void deactivateKeyboard() {
			calls.add("deactivate");
		}
		
		@Override
		public void pressKey(int scancode) {
			calls.add("press:" + scancode);
		}
		
		@Override
		public void releaseKey(int scancode) {
			calls.add("release:" + scancode);
		}
	}
	
}
