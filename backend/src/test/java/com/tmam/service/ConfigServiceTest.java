package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import com.tmam.model.PortConfig;
import com.tmam.model.PortConflict;
import com.tmam.model.ProjectConfig;
import com.tmam.model.TmamConfig;
import com.tmam.model.TomcatInstanceConfig;

class ConfigServiceTest {

	@TempDir
	Path tempDir;

	private ConfigService configService;

	@BeforeEach
	void setUp() {
		XmlConfiguratorService xmlConfiguratorService = new XmlConfiguratorService(
				new ClassPathResource("server-template.xml"));
		NativeTomcatEnvironmentService nativeTomcatEnvironmentService = new NativeTomcatEnvironmentService(
				tempDir.resolve("instances").toString(), xmlConfiguratorService);
		ServerXmlService serverXmlService = new ServerXmlService(
				"C:/Program Files/apache-tomcat-9.0.115",
				"PathGateway",
				nativeTomcatEnvironmentService);
		ConfigMigrationService migrationService = new ConfigMigrationService(
				tempDir.resolve("projects.json").toString(),
				tempDir.resolve("instances").toString(),
				tempDir.resolve("fragments").toString(),
				tempDir.resolve("native-base").toString(),
				"C:/Program Files/apache-tomcat-9.0.115");
		configService = new ConfigService(serverXmlService, migrationService);
		ReflectionTestUtils.setField(configService, "configPath",
				tempDir.resolve("projects.json").toString());
	}

	@Test
	void loadCreatesEmptyConfigWhenMissing() throws IOException {
		TmamConfig config = configService.load();

		assertEquals(TmamConfig.VERSION_2, config.getVersion());
		assertTrue(config.getTomcatInstances().isEmpty());
		assertTrue(Files.exists(tempDir.resolve("projects.json")));
	}

	@Test
	void saveAndLoadRoundTrip() throws IOException {
		TmamConfig config = new TmamConfig();
		config.setVersion(TmamConfig.VERSION_2);
		TomcatInstanceConfig instance = new TomcatInstanceConfig();
		instance.setId(TomcatInstanceConfig.DEFAULT_ID);
		instance.setCatalinaHome("C:/tomcat");
		instance.setDisplayName("tomcat");
		config.getTomcatInstances().put(TomcatInstanceConfig.DEFAULT_ID, instance);

		ProjectConfig project = new ProjectConfig();
		project.setDisplayName("CRM");
		project.setPorts(new PortConfig(8081, 8005, 8009));
		config.getProjects().put("crm-system", project);

		configService.save(config);
		TmamConfig loaded = configService.load();

		assertEquals("crm-system", loaded.getProjects().get("crm-system").getName());
		assertEquals("CRM", loaded.getProjects().get("crm-system").getDisplayName());
		assertEquals(8081, loaded.getProjects().get("crm-system").getPorts().http());
	}

	@Test
	void detectPortConflictsFindsDuplicates() {
		TmamConfig config = new TmamConfig();
		config.setProjects(new LinkedHashMap<>());

		ProjectConfig a = project("a", 8081, 8005, 8009);
		ProjectConfig b = project("b", 8082, 8005, 8019);
		config.getProjects().put("project-a", a);
		config.getProjects().put("project-b", b);

		var conflicts = configService.detectPortConflicts(config);

		assertEquals(1, conflicts.size());
		PortConflict conflict = conflicts.get(0);
		assertEquals(8005, conflict.port());
		assertEquals("Shutdown", conflict.type());
		assertEquals("project-a", conflict.projectA());
		assertEquals("project-b", conflict.projectB());
	}

	@Test
	void detectPortConflictsIgnoresDisabledProjects() {
		TmamConfig config = new TmamConfig();
		config.setProjects(new LinkedHashMap<>());

		ProjectConfig a = project("a", 8081, 8005, 8009);
		ProjectConfig b = project("b", 8081, 8005, 8009);
		b.setEnabled(false);
		config.getProjects().put("project-a", a);
		config.getProjects().put("project-b", b);

		assertTrue(configService.detectPortConflicts(config).isEmpty());
	}

	@Test
	void detectPortConflictsReturnsEmptyWhenNoConflict() {
		TmamConfig config = new TmamConfig();
		config.getProjects().put("project-a", project("a", 8081, 8005, 8009));
		config.getProjects().put("project-b", project("b", 8082, 8015, 8019));

		assertTrue(configService.detectPortConflicts(config).isEmpty());
	}

	@Test
	void loadRemovesDuplicateTomcatInstancesWithSameCatalinaHome() throws IOException {
		TmamConfig config = new TmamConfig();
		config.setVersion(TmamConfig.VERSION_2);
		Map<String, TomcatInstanceConfig> instances = new LinkedHashMap<>();

		TomcatInstanceConfig defaultInstance = new TomcatInstanceConfig();
		defaultInstance.setId(TomcatInstanceConfig.DEFAULT_ID);
		defaultInstance.setCatalinaHome("C:/Program Files/apache-tomcat-9.0.115");
		defaultInstance.setDisplayName("apache-tomcat-9.0.115");
		defaultInstance.setGatewayPort(8080);
		defaultInstance.setShutdownPort(8005);
		instances.put(TomcatInstanceConfig.DEFAULT_ID, defaultInstance);

		TomcatInstanceConfig duplicate = new TomcatInstanceConfig();
		duplicate.setId("apache-tomcat-9_0_115");
		duplicate.setCatalinaHome("C:\\Program Files\\apache-tomcat-9.0.115");
		duplicate.setDisplayName("apache-tomcat-9.0.115");
		duplicate.setGatewayPort(8081);
		duplicate.setShutdownPort(8006);
		instances.put("apache-tomcat-9_0_115", duplicate);

		config.setTomcatInstances(instances);
		configService.save(config);

		TmamConfig loaded = configService.load();

		assertEquals(1, loaded.getTomcatInstances().size());
		assertTrue(loaded.getTomcatInstances().containsKey(TomcatInstanceConfig.DEFAULT_ID));
	}

	private ProjectConfig project(String displayName, int http, int shutdown, int ajp) {
		ProjectConfig project = new ProjectConfig();
		project.setDisplayName(displayName);
		project.setPorts(new PortConfig(http, shutdown, ajp));
		return project;
	}

}
