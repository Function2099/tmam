package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeTomcatEnvironmentServiceTest {

	@TempDir
	Path tempDir;

	private NativeTomcatEnvironmentService service;
	private XmlConfiguratorService xmlConfiguratorService;

	@BeforeEach
	void setUp() {
		xmlConfiguratorService = mock(XmlConfiguratorService.class);
		service = new NativeTomcatEnvironmentService(tempDir.resolve("instances").toString(), xmlConfiguratorService);
	}

	@Test
	void ensureInitializedSkipsRepeatCallsForSameInstance() throws Exception {
		Path catalinaHome = tempDir.resolve("tomcat-home");
		Files.createDirectories(catalinaHome.resolve("conf"));

		service.ensureInitialized("default", catalinaHome.toString());
		service.ensureInitialized("default", catalinaHome.toString());

		verify(xmlConfiguratorService, times(1)).copyFromHome(catalinaHome, service.getCatalinaBase("default"));
		assertTrue(Files.isDirectory(service.getCatalinaBase("default").resolve("conf")));
	}

	@Test
	void invalidateAllowsReinitialization() throws Exception {
		Path catalinaHome = tempDir.resolve("tomcat-home");
		Files.createDirectories(catalinaHome.resolve("conf"));

		service.ensureInitialized("default", catalinaHome.toString());
		service.invalidate("default");
		service.ensureInitialized("default", catalinaHome.toString());

		verify(xmlConfiguratorService, times(2)).copyFromHome(catalinaHome, service.getCatalinaBase("default"));
	}

}
