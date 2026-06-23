package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;

class PathProxyValidatorTest {

	@TempDir
	Path tempDir;

	@Test
	void normalizePathPrefixAddsLeadingSlash() {
		assertEquals("/new-system", PathProxyValidator.normalizePathPrefix("new-system"));
		assertEquals("/portal", PathProxyValidator.normalizePathPrefix("/portal/"));
	}

	@Test
	void validateDocBaseRequiresExistingDirectory() throws Exception {
		Path docBase = tempDir.resolve("web");
		Files.createDirectories(docBase);
		PathProxyValidator.validateDocBase(docBase.toString());
		assertThrows(IllegalArgumentException.class, () -> PathProxyValidator.validateDocBase("D:/missing-webapp"));
	}

	@Test
	void validatePathPrefixDetectsConflict() {
		TomcatServiceConfig existing = new TomcatServiceConfig();
		existing.setName("A");
		existing.setType(TomcatServiceType.PATH_PROXY);
		existing.setPathPrefix("/portal");

		assertThrows(IllegalArgumentException.class,
				() -> PathProxyValidator.validatePathPrefix("/portal", java.util.List.of(existing), null));
	}

}
