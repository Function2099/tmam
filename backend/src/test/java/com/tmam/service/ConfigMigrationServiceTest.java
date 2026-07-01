package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tmam.model.TmamConfig;
import com.tmam.model.TomcatInstanceConfig;
import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;

class ConfigMigrationServiceTest {

	@TempDir
	Path tempDir;

	private ConfigMigrationService migrationService;

	@BeforeEach
	void setUp() {
		CatalinaHomeResolver catalinaHomeResolver = new CatalinaHomeResolver(
				new TomcatDiscoveryService(), "C:/tomcat-default");
		migrationService = new ConfigMigrationService(
				tempDir.resolve("projects.json").toString(),
				tempDir.resolve("instances").toString(),
				tempDir.resolve("legacy-fragments").toString(),
				tempDir.resolve("legacy-native").toString(),
				catalinaHomeResolver);
	}

	@Test
	void v2EmptyConfigDoesNotMigrate() throws Exception {
		TmamConfig config = new TmamConfig();
		config.setVersion(TmamConfig.VERSION_2);
		config.setTomcatInstances(new java.util.LinkedHashMap<>());

		assertFalse(migrationService.migrateIfNeeded(config));
		assertTrue(config.getTomcatInstances().isEmpty());
	}

	@Test
	void migratesV1ConfigToDefaultInstance() throws Exception {
		Path legacyFragments = tempDir.resolve("legacy-fragments");
		Files.createDirectories(legacyFragments);
		Files.writeString(legacyFragments.resolve("server-header.xml"), "<Server port=\"8005\">");

		TmamConfig config = new TmamConfig();
		config.setVersion("1.0.0");
		config.setCatalinaHome("D:/old-tomcat");
		TomcatServiceConfig service = new TomcatServiceConfig();
		service.setName("Portal");
		service.setType(TomcatServiceType.LEGACY_IP);
		config.getServices().put("Portal", service);

		boolean migrated = migrationService.migrateIfNeeded(config);

		assertTrue(migrated);
		assertEquals(TmamConfig.VERSION_2, config.getVersion());
		assertTrue(config.getTomcatInstances().containsKey(TomcatInstanceConfig.DEFAULT_ID));
		assertEquals("D:/old-tomcat",
				config.getTomcatInstances().get(TomcatInstanceConfig.DEFAULT_ID).getCatalinaHome());
		assertTrue(config.getTomcatInstances().get(TomcatInstanceConfig.DEFAULT_ID).getServices()
				.containsKey("Portal"));
		assertTrue(Files.exists(
				tempDir.resolve("instances/default/server-fragments/server-header.xml")));
	}

}
