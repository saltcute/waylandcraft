package dev.evvie.waylandcraft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Structural check that the shipped mixin cancel site is at HEAD (before
 * KeyMapping.set / pauseGame), not the broken 26.2 ordinal=1 getKey inject.
 */
public class KeyboardHandlerMixinTargetTest {
	
	private static Path projectRoot() {
		Path cwd = Path.of("").toAbsolutePath();
		if (Files.isRegularFile(cwd.resolve("gradle.properties"))) {
			return cwd;
		}
		Path p = cwd;
		for (int i = 0; i < 4; i++) {
			if (Files.isRegularFile(p.resolve("gradle.properties"))) {
				return p;
			}
			p = p.getParent();
			if (p == null) break;
		}
		return cwd;
	}
	
	@Test
	void mixinCancelsAtHeadNotBrokenOrdinal1() throws IOException {
		Path mixin = projectRoot().resolve("src/main/java/dev/evvie/waylandcraft/mixin/KeyboardHandlerMixin.java");
		assertTrue(Files.isRegularFile(mixin), "KeyboardHandlerMixin must exist");
		String src = Files.readString(mixin, StandardCharsets.UTF_8);
		
		assertTrue(src.contains("@At(\"HEAD\")") || src.contains("@At(value = \"HEAD\")"),
				"keyPress inject must be at HEAD so cancel runs before KeyMapping/pauseGame");
		assertTrue(src.contains("cancellable = true"),
				"keyPress inject must be cancellable to block vanilla actions");
		assertTrue(src.contains("onKeyPress("),
				"mixin must call WaylandCraft.onKeyPress");
		assertTrue(src.contains("info.cancel()"),
				"mixin must cancel when capture claims the event");
		
		// The pre-fix inject used ordinal = 1 on InputConstants.getKey, which only
		// fires on the open-screen path in 26.2 and never while in-game.
		assertFalse(src.contains("ordinal = 1"),
				"must not use the broken getKey ordinal=1 inject site");
		assertFalse(src.contains("InputConstants;getKey"),
				"must not depend on mid-method getKey inject for capture cancel");
	}
	
}
