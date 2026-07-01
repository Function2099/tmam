package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import com.tmam.model.PortConfig;
import com.tmam.model.ProjectConfig;

class EnvironmentServiceTest {

	@TempDir
	Path tempDir;

	private EnvironmentService environmentService;

	@BeforeEach
	void setUp() {
		Path instancesRoot = tempDir.resolve("instances");
		Path catalinaHome = tempDir.resolve("catalina-home");
		var xmlService = new XmlConfiguratorService(
				new DefaultResourceLoader().getResource("classpath:server-template.xml"));
		CatalinaHomeResolver catalinaHomeResolver = new CatalinaHomeResolver(
				new TomcatDiscoveryService(), catalinaHome.toString());
		environmentService = new EnvironmentService(
				instancesRoot.toString(),
				catalinaHomeResolver,
				xmlService);
	}

	@Test
	void initializeCreatesInstanceLayoutAndServerXml() throws Exception {
		Path catalinaHome = tempDir.resolve("catalina-home");
		Files.createDirectories(catalinaHome.resolve("conf"));
		Files.writeString(catalinaHome.resolve("conf/web.xml"), "<web-app/>");

		ProjectConfig project = new ProjectConfig();
		project.setName("crm-system");
		project.setPorts(new PortConfig(8081, 8015, 8019));
		project.setContextPath("ROOT");

		environmentService.initialize(project);

		Path base = environmentService.getInstanceBase("crm-system");
		for (String dir : new String[] { "conf", "logs", "temp", "work", "webapps" }) {
			assertTrue(Files.isDirectory(base.resolve(dir)), "missing dir: " + dir);
		}

		assertTrue(Files.exists(base.resolve("conf/web.xml")));
		assertTrue(Files.exists(base.resolve("conf/server.xml")));

		var doc = DocumentBuilderFactory.newInstance()
				.newDocumentBuilder()
				.parse(base.resolve("conf/server.xml").toFile());
		assertEquals("8015", doc.getDocumentElement().getAttribute("port"));
	}

	@Test
	void initializeDeploysWarToWebapps() throws Exception {
		Path catalinaHome = tempDir.resolve("catalina-home");
		Files.createDirectories(catalinaHome.resolve("conf"));
		Files.writeString(catalinaHome.resolve("conf/web.xml"), "<web-app/>");

		Path war = tempDir.resolve("sample.war");
		Files.writeString(war, "fake-war-content");

		ProjectConfig project = new ProjectConfig();
		project.setName("erp-backend");
		project.setPorts(new PortConfig(8082, 8025, 8029));
		project.setWarPath(war.toString());
		project.setContextPath("ROOT");

		environmentService.initialize(project);

		Path deployed = environmentService.getInstanceBase("erp-backend").resolve("webapps/ROOT.war");
		assertTrue(Files.exists(deployed));
		assertEquals("fake-war-content", Files.readString(deployed));
	}

}
