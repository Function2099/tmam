package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NginxDiscoveryServiceTest {

	@TempDir
	Path tempDir;

	private final NginxDiscoveryService discovery = new NginxDiscoveryService();

	@Test
	void recognizesNginxExecutable() throws Exception {
		Path executable = tempDir.resolve("nginx.exe");
		Files.writeString(executable, "fake");

		assertTrue(discovery.isNginxExecutable(executable));
	}

	@Test
	void rejectsMissingExecutable() {
		assertFalse(discovery.isNginxExecutable(tempDir.resolve("missing.exe")));
	}

}
