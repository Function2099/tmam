package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tmam.model.TmamConfig;
import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;

class PathGatewayServiceTest {

	@TempDir
	Path tempDir;

	private PathGatewayService pathGatewayService;

	@BeforeEach
	void setUp() {
		pathGatewayService = new PathGatewayService(
				"PathGateway",
				"127.0.0.1",
				8080,
				tempDir.resolve("fragments").toString());
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

		pathGatewayService.writeFragment(List.of(service));

		String fragment = Files.readString(pathGatewayService.fragmentPath());
		assertTrue(fragment.contains("<Service name=\"PathGateway\">"));
		assertTrue(fragment.contains("address=\"127.0.0.1\""));
		assertTrue(fragment.contains("port=\"8080\""));
		assertTrue(fragment.contains("path=\"/new-system\""));
		assertTrue(fragment.contains(docBase.toString()));
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
		pathGatewayService.writeFragment(List.of(service));
		assertTrue(Files.exists(pathGatewayService.fragmentPath()));

		service.setEnabled(false);
		pathGatewayService.writeFragment(List.of(service));
		assertFalse(Files.exists(pathGatewayService.fragmentPath()));
	}

}
