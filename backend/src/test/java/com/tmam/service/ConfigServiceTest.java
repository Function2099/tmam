package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import com.tmam.model.PortConfig;
import com.tmam.model.PortConflict;
import com.tmam.model.ProjectConfig;
import com.tmam.model.TmamConfig;

class ConfigServiceTest {

	@TempDir
	Path tempDir;

	private ConfigService configService;

	@BeforeEach
	void setUp() {
		XmlConfiguratorService xmlConfiguratorService = new XmlConfiguratorService(
				new ClassPathResource("server-template.xml"));
		NativeTomcatEnvironmentService nativeTomcatEnvironmentService = new NativeTomcatEnvironmentService(
				tempDir.resolve("native-base").toString(), xmlConfiguratorService);
		ServerXmlService serverXmlService = new ServerXmlService(
				"C:/Program Files/apache-tomcat-9.0.115",
				"conf/server.xml.tmam-original",
				tempDir.resolve("fragments").toString(),
				"PathGateway",
				nativeTomcatEnvironmentService);
		configService = new ConfigService(serverXmlService);
		ReflectionTestUtils.setField(configService, "configPath",
				tempDir.resolve("projects.json").toString());
		ReflectionTestUtils.setField(configService, "defaultCatalinaHome",
				"C:/Program Files/apache-tomcat-9.0.115");
	}

	@Test
	void loadCreatesDefaultConfigWhenMissing() throws IOException {
		TmamConfig config = configService.load();

		assertEquals("1.0.0", config.getVersion());
		assertEquals("C:/Program Files/apache-tomcat-9.0.115", config.getCatalinaHome());
		assertTrue(Files.exists(tempDir.resolve("projects.json")));
	}

	@Test
	void saveAndLoadRoundTrip() throws IOException {
		TmamConfig config = new TmamConfig();
		config.setCatalinaHome("C:/tomcat");

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

	private ProjectConfig project(String displayName, int http, int shutdown, int ajp) {
		ProjectConfig project = new ProjectConfig();
		project.setDisplayName(displayName);
		project.setPorts(new PortConfig(http, shutdown, ajp));
		return project;
	}

}
