package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import com.tmam.model.TomcatInstanceConfig;
import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;

class ServerXmlServiceTest {

	private static final String INSTANCE_ID = TomcatInstanceConfig.DEFAULT_ID;

	@TempDir
	Path tempDir;

	private ServerXmlService serverXmlService;
	private PathGatewayService pathGatewayService;
	private Path catalinaBase;

	@BeforeEach
	void setUp() {
		Path instancesRoot = tempDir.resolve("instances");
		catalinaBase = instancesRoot.resolve(INSTANCE_ID).resolve("catalina-base");
		XmlConfiguratorService xmlConfiguratorService = new XmlConfiguratorService(
				new ClassPathResource("server-template.xml"));
		NativeTomcatEnvironmentService nativeTomcatEnvironmentService = new NativeTomcatEnvironmentService(
				instancesRoot.toString(), xmlConfiguratorService);
		serverXmlService = new ServerXmlService(
				tempDir.toString(),
				"PathGateway",
				nativeTomcatEnvironmentService);
		pathGatewayService = new PathGatewayService(
				"PathGateway",
				"127.0.0.1",
				nativeTomcatEnvironmentService);
	}

	@Test
	void importAndComposeServerXml() throws Exception {
		Path catalinaHome = tempDir.resolve("tomcat");
		Files.createDirectories(catalinaHome.resolve("conf"));
		Files.copy(Path.of("src/test/resources/sample-server.xml"), catalinaHome.resolve("conf/server.xml"));

		List<TomcatServiceConfig> imported = serverXmlService.importFromServerXml(INSTANCE_ID,
				catalinaHome.toString());
		assertEquals(9, imported.size());
		assertEquals("Portal_Area", imported.get(0).getName());
		assertEquals("192.168.10.10", imported.get(0).getAddress());
		assertEquals(36, imported.get(0).getPort());

		Map<String, TomcatServiceConfig> services = new LinkedHashMap<>();
		imported.forEach(service -> {
			service.setEnabled(!"Portal_Sport".equals(service.getName()));
			services.put(service.getName(), service);
		});

		serverXmlService.writeEffectiveServerXml(INSTANCE_ID, catalinaHome.toString(), services);

		String effective = Files.readString(catalinaBase.resolve("conf/server.xml"));
		assertTrue(effective.contains("<Service name=\"Portal_Area\">"));
		assertFalse(effective.contains("<Service name=\"Portal_Sport\">"));
		assertTrue(Files.exists(serverXmlService.backupPath(INSTANCE_ID, catalinaHome.toString())));
	}

	@Test
	void composeServerXmlWithPathGateway() throws Exception {
		Path catalinaHome = tempDir.resolve("tomcat-gateway");
		Files.createDirectories(catalinaHome.resolve("conf"));
		Files.copy(Path.of("src/test/resources/sample-server.xml"), catalinaHome.resolve("conf/server.xml"));

		List<TomcatServiceConfig> imported = serverXmlService.importFromServerXml(INSTANCE_ID,
				catalinaHome.toString());
		Map<String, TomcatServiceConfig> services = new LinkedHashMap<>();
		imported.forEach(service -> {
			service.setEnabled("Portal_Area".equals(service.getName()));
			services.put(service.getName(), service);
		});

		Path docBase = tempDir.resolve("new-system-web");
		Files.createDirectories(docBase);
		TomcatServiceConfig pathProxy = new TomcatServiceConfig();
		pathProxy.setName("New_System");
		pathProxy.setType(TomcatServiceType.PATH_PROXY);
		pathProxy.setPathPrefix("/new-system");
		pathProxy.setDocBase(docBase.toString());
		pathProxy.setEnabled(true);
		services.put(pathProxy.getName(), pathProxy);

		pathGatewayService.writeFragment(INSTANCE_ID, 8080, services.values());
		serverXmlService.writeEffectiveServerXml(INSTANCE_ID, catalinaHome.toString(), services);

		String effective = Files.readString(catalinaBase.resolve("conf/server.xml"));
		assertTrue(effective.contains("<Service name=\"Portal_Area\">"));
		assertTrue(effective.contains("<Service name=\"PathGateway\">"));
		assertTrue(effective.contains("path=\"/new-system\""));
	}

	@Test
	void mergeImportedServicesPreservesPathProxy() {
		TomcatServiceConfig legacy = new TomcatServiceConfig();
		legacy.setName("Portal_Area");
		legacy.setType(TomcatServiceType.LEGACY_IP);

		TomcatServiceConfig pathProxy = new TomcatServiceConfig();
		pathProxy.setName("New_System");
		pathProxy.setType(TomcatServiceType.PATH_PROXY);
		pathProxy.setPathPrefix("/new-system");

		Map<String, TomcatServiceConfig> existing = new LinkedHashMap<>();
		existing.put(pathProxy.getName(), pathProxy);

		Map<String, TomcatServiceConfig> merged = serverXmlService.mergeImportedServices(List.of(legacy), existing);

		assertTrue(merged.containsKey("Portal_Area"));
		assertTrue(merged.containsKey("New_System"));
		assertEquals(TomcatServiceType.PATH_PROXY, merged.get("New_System").getType());
	}

	@Test
	void restoreOriginalServerXml() throws Exception {
		Path catalinaHome = tempDir.resolve("tomcat-restore");
		Files.createDirectories(catalinaHome.resolve("conf"));
		Files.copy(Path.of("src/test/resources/sample-server.xml"), catalinaHome.resolve("conf/server.xml"));

		serverXmlService.importFromServerXml(INSTANCE_ID, catalinaHome.toString());
		Map<String, TomcatServiceConfig> services = new LinkedHashMap<>();
		TomcatServiceConfig area = new TomcatServiceConfig();
		area.setName("Portal_Area");
		area.setType(TomcatServiceType.LEGACY_IP);
		area.setEnabled(true);
		services.put("Portal_Area", area);

		serverXmlService.writeEffectiveServerXml(INSTANCE_ID, catalinaHome.toString(), services);
		serverXmlService.restoreOriginal(INSTANCE_ID, catalinaHome.toString());

		String restored = Files.readString(catalinaBase.resolve("conf/server.xml"));
		assertTrue(restored.contains("<Service name=\"Portal_CTSP\">"));
	}

}
