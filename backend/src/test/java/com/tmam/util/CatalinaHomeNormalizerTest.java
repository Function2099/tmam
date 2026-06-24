package com.tmam.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalinaHomeNormalizerTest {

	@TempDir
	Path tempDir;

	@Test
	void comparisonKeyTreatsForwardAndBackslashesAsSame() throws Exception {
		Path home = tempDir.resolve("apache-tomcat-9.0.test");
		Files.createDirectories(home);

		String forward = home.toString().replace('\\', '/');
		String backward = home.toString();

		assertEquals(
				CatalinaHomeNormalizer.comparisonKey(forward),
				CatalinaHomeNormalizer.comparisonKey(backward));
		assertTrue(CatalinaHomeNormalizer.isSame(forward, backward));
	}

	@Test
	void isSameReturnsFalseForDifferentPaths() throws Exception {
		Path homeA = tempDir.resolve("tomcat-a");
		Path homeB = tempDir.resolve("tomcat-b");
		Files.createDirectories(homeA);
		Files.createDirectories(homeB);

		assertFalse(CatalinaHomeNormalizer.isSame(homeA.toString(), homeB.toString()));
	}

}
