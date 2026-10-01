package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tmam.model.TomcatInstanceConfig;
import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;

class PathGatewayServiceTest {

	private static final String INSTANCE_ID = TomcatInstanceConfig.DEFAULT_ID;

	@TempDir
	Path tempDir;

	private PathGatewayService pathGatewayService;

	@BeforeEach
	void setUp() {
		Path instancesRoot = tempDir.resolve("instances");
		NativeTomcatEnvironmentService nativeTomcatEnvironmentService = new NativeTomcatEnvironmentService(
				instancesRoot.toString());
		pathGatewayService = new PathGatewayService(
				"PathGateway",
				"127.0.0.1",
				nativeTomcatEnvironmentService);
	}

	@Test
	void writeFragmentCreatesServiceWithContexts() throws Exception {
		Path docBase = tempDir.resolve("webapp");
		Files.createDirectories(docBase);

		TomcatServiceConfig service = new TomcatServiceConfig();
		service.setName("New_System");
		service.setType(TomcatServiceType.PATH_PROXY);
		service.setPathPrefix("/new-system");
		service.setDocBase(docBase.toString());
		service.setEnabled(true);

		pathGatewayService.writeFragment(INSTANCE_ID, 8080, List.of(service));

		String fragment = Files.readString(pathGatewayService.fragmentPath(INSTANCE_ID));
		assertTrue(fragment.contains("<Service name=\"PathGateway\">"));
		assertTrue(fragment.contains("defaultHost=\"localhost\""));
		assertTrue(fragment.contains("<Host name=\"localhost\""));
		assertTrue(fragment.contains("<Alias>127.0.0.1</Alias>"));
		assertTrue(fragment.contains("address=\"127.0.0.1\""));
		assertTrue(fragment.contains("port=\"8080\""));
		assertTrue(fragment.contains("path=\"/new-system\""));
		assertTrue(fragment.contains("sessionCookiePath=\"/\""));
		assertTrue(fragment.contains(docBase.toString()));
		assertTrue(fragment.contains("name=\"tmam.online\""));
		assertTrue(fragment.contains("value=\"false\""));
		assertTrue(fragment.contains("startStopThreads=\"0\""));
		assertTrue(fragment.contains("autoDeploy=\"false\""));
		assertTrue(fragment.contains("<JarScanner"));
	}

	@Test
	void writeFragmentIncludesOnlineParameterWhenEnabled() throws Exception {
		Path docBase = tempDir.resolve("webapp-online");
		Files.createDirectories(docBase);

		TomcatServiceConfig service = new TomcatServiceConfig();
		service.setName("Prod_System");
		service.setType(TomcatServiceType.PATH_PROXY);
		service.setPathPrefix("/prod");
		service.setDocBase(docBase.toString());
		service.setEnabled(true);
		service.setOnline(true);

		pathGatewayService.writeFragment(INSTANCE_ID, 8080, List.of(service));

		String fragment = Files.readString(pathGatewayService.fragmentPath(INSTANCE_ID));
		assertTrue(fragment.contains("name=\"tmam.online\""));
		assertTrue(fragment.contains("value=\"true\""));
	}

	@Test
	void writeFragmentRemovesFileWhenNoEnabledServices() throws Exception {
		Path docBase = tempDir.resolve("webapp");
		Files.createDirectories(docBase);

		TomcatServiceConfig service = new TomcatServiceConfig();
		service.setName("New_System");
		service.setType(TomcatServiceType.PATH_PROXY);
		service.setPathPrefix("/new-system");
		service.setDocBase(docBase.toString());
		service.setEnabled(true);
		pathGatewayService.writeFragment(INSTANCE_ID, 8080, List.of(service));
		assertTrue(Files.exists(pathGatewayService.fragmentPath(INSTANCE_ID)));

		service.setEnabled(false);
		pathGatewayService.writeFragment(INSTANCE_ID, 8080, List.of(service));
		assertFalse(Files.exists(pathGatewayService.fragmentPath(INSTANCE_ID)));
	}

}
