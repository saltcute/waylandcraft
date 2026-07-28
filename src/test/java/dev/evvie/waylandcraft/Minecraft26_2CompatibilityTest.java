package dev.evvie.waylandcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Structural tests that the shipped toolchain/metadata target Minecraft 26.2.
 * These read the real project files used by Gradle and Fabric Loader.
 */
public class Minecraft26_2CompatibilityTest {

	private static Path projectRoot() {
		Path cwd = Path.of("").toAbsolutePath();
		if (Files.isRegularFile(cwd.resolve("gradle.properties"))) {
			return cwd;
		}
		// When run from a nested working directory, walk up.
		Path p = cwd;
		for (int i = 0; i < 4; i++) {
			if (Files.isRegularFile(p.resolve("gradle.properties"))) {
				return p;
			}
			p = p.getParent();
			if (p == null) {
				break;
			}
		}
		return cwd;
	}

	@Test
	void gradlePropertiesTargetMinecraft26_2() throws IOException {
		Path propsPath = projectRoot().resolve("gradle.properties");
		assertTrue(Files.isRegularFile(propsPath), "gradle.properties must exist at " + propsPath);

		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(propsPath)) {
			props.load(in);
		}

		assertEquals("26.2", props.getProperty("minecraft_version"),
				"minecraft_version must be 26.2");
		assertEquals("0.19.3", props.getProperty("loader_version"),
				"Fabric Loader must match Fabric develop pin for 26.2");
		assertEquals("1.17-SNAPSHOT", props.getProperty("loom_version"),
				"Loom must match Fabric develop pin for 26.2");
		assertEquals("0.155.2+26.2", props.getProperty("fabric_version"),
				"Fabric API must be the 26.2-compatible version");
		assertTrue(props.getProperty("iris_version", "").contains("26.2"),
				"Iris compileOnly pin should be 26.2-compatible, got: " + props.getProperty("iris_version"));
	}

	@Test
	void fabricModJsonDependsOnMinecraft26_2() throws IOException {
		Path modJson = projectRoot().resolve("src/main/resources/fabric.mod.json");
		assertTrue(Files.isRegularFile(modJson), "fabric.mod.json must exist");

		String json = Files.readString(modJson, StandardCharsets.UTF_8);

		Matcher minecraft = Pattern.compile("\"minecraft\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
		assertTrue(minecraft.find(), "fabric.mod.json must declare a minecraft dependency");
		String minecraftDep = minecraft.group(1);
		assertTrue(minecraftDep.contains("26.2"),
				"depends.minecraft must accept 26.2, got: " + minecraftDep);
		assertFalse(minecraftDep.contains("26.1"),
				"depends.minecraft must not still pin 26.1.x, got: " + minecraftDep);

		Matcher loader = Pattern.compile("\"fabricloader\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
		assertTrue(loader.find(), "fabric.mod.json must declare fabricloader dependency");
		assertTrue(loader.group(1).contains("0.19.3") || loader.group(1).contains("0.19"),
				"fabricloader depend should require 0.19.3+, got: " + loader.group(1));
	}

	@Test
	void packagedJarDeclaresMinecraft26_2WhenPresent() throws IOException {
		Path jar = projectRoot().resolve("build/libs/waylandcraft.jar");
		if (!Files.isRegularFile(jar)) {
			// Build artifact is produced by `./gradlew build`; skip when only unit tests run.
			return;
		}
		assertTrue(Files.size(jar) > 0, "waylandcraft.jar must be non-empty");

		try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar.toFile())) {
			var entry = jarFile.getEntry("fabric.mod.json");
			assertNotNull(entry, "packaged jar must contain fabric.mod.json");
			String json = new String(jarFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(json.contains("\"minecraft\"") && json.contains("26.2"),
					"packaged fabric.mod.json must depend on 26.2, got: " + json);
			assertFalse(json.contains("26.1.2"),
					"packaged fabric.mod.json must not still claim 26.1.2");
		}
	}

	@Test
	void readmeDocumentsMinecraft26_2() throws IOException {
		Path readme = projectRoot().resolve("README.md");
		assertTrue(Files.isRegularFile(readme), "README.md must exist");
		String text = Files.readString(readme, StandardCharsets.UTF_8);
		assertTrue(text.contains("Minecraft 26.2") || text.contains("for 26.2"),
				"README must document Minecraft 26.2");
		assertFalse(text.contains("26.1.2"),
				"README must not still claim Minecraft 26.1.2");
	}
}
