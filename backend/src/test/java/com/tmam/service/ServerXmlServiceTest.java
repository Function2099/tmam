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

import com.tmam.model.TomcatInstanceConfig;
import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;

class ServerXmlServiceTest {

	private static final String INSTANCE_ID = TomcatInstanceConfig.DEFAULT_ID;

	@TempDir
	Path tempDir;

	private ServerXmlService serverXmlService;
	private PathGatewayService pathGatewayService;

	@BeforeEach
	void setUp() {
		Path instancesRoot = tempDir.resolve("instances");
		NativeTomcatEnvironmentService nativeTomcatEnvironmentService = new NativeTomcatEnvironmentService(
				instancesRoot.toString());
		serverXmlService = new ServerXmlService(
				new CatalinaHomeResolver(new TomcatDiscoveryService(), tempDir.toString()),
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
		assertTrue(imported.stream().allMatch(service -> "192.168.10.10".equals(service.getAddress())));
		assertEquals(9, imported.stream().map(TomcatServiceConfig::getPort).distinct().count());

		Map<String, TomcatServiceConfig> services = new LinkedHashMap<>();
		imported.forEach(service -> {
			service.setEnabled(!"Portal_Sport".equals(service.getName()));
			services.put(service.getName(), service);
		});

		serverXmlService.writeEffectiveServerXml(INSTANCE_ID, catalinaHome.toString(), services);

		String effective = Files.readString(catalinaHome.resolve("conf/server.xml"));
		assertTrue(effective.contains("<Service name=\"Portal_Area\">"));
		assertFalse(effective.contains("<Service name=\"Portal_Sport\">"));
		assertTrue(effective.contains("<Server port=\"8005\" shutdown=\"SHUTDOWN\" startStopThreads=\"0\">"));
		assertTrue(effective.contains("startStopThreads=\"0\""));
		assertTrue(effective.contains("<JarScanner"));
		assertTrue(Files.exists(serverXmlService.backupPath(INSTANCE_ID, catalinaHome.toString())));
		assertTrue(Files.notExists(tempDir.resolve("instances").resolve(INSTANCE_ID).resolve("catalina-base")));
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

		String effective = Files.readString(catalinaHome.resolve("conf/server.xml"));
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

		String restored = Files.readString(catalinaHome.resolve("conf/server.xml"));
		assertTrue(restored.contains("<Service name=\"Portal_CTSP\">"));
	}

	@Test
	void patchLegacyIpFragmentPreservesExtraContextSettings() throws Exception {
		Path fragments = tempDir.resolve("instances").resolve(INSTANCE_ID).resolve("server-fragments");
		Files.createDirectories(fragments);
		Files.writeString(fragments.resolve("Portal.xml"), """
				<Service name="Portal">
				  <Connector URIEncoding="utf-8" address="192.168.10.10" port="33" protocol="HTTP/1.1" />
				  <Engine name="Portal" defaultHost="Portal">
				    <Host name="Portal" unpackWARs="true" autoDeploy="true">
				      <Context sessionCookieName="cloudSessionId" path="" docBase="D:\\Work_Java\\Portal\\web" reloadable="true" crossContext="true">
				        <Resources cachingAllowed="false" cacheMaxSize="100000"/>
				      </Context>
				    </Host>
				  </Engine>
				</Service>
				""");

		TomcatServiceConfig service = new TomcatServiceConfig();
		service.setName("Portal");
		service.setAddress("192.168.10.10");
		service.setPort(33);
		service.setDocBase("D:\\Work_Java\\Portal\\web");
		service.setOnline(true);
		serverXmlService.patchLegacyIpFragment(INSTANCE_ID, "Portal", service);

		String patched = Files.readString(fragments.resolve("Portal.xml"));
		assertTrue(patched.contains("sessionCookieName=\"cloudSessionId\""));
		assertTrue(patched.contains("docBase=\"D:\\Work_Java\\Portal\\web\""));
		assertTrue(patched.contains("<Resources cachingAllowed=\"false\""));
		assertTrue(patched.contains("name=\"tmam.online\""));
		assertTrue(patched.contains("value=\"true\""));
	}

	@Test
	void patchLegacyIpFragmentUpdatesConnectorWithoutTouchingRedirectPort() throws Exception {
		Path fragments = tempDir.resolve("instances").resolve(INSTANCE_ID).resolve("server-fragments");
		Files.createDirectories(fragments);
		Files.writeString(fragments.resolve("Portal.xml"), """
				<Service name="Portal">
				  <Connector URIEncoding="utf-8" address="192.168.10.10" port="36" protocol="HTTP/1.1"
				    redirectPort="443" />
				  <Engine name="Portal" defaultHost="Portal">
				    <Host name="Portal" unpackWARs="true" autoDeploy="true">
				      <Context path="" docBase="D:\\Work_Java\\Portal\\web" reloadable="true">
				      </Context>
				    </Host>
				  </Engine>
				</Service>
				""");

		TomcatServiceConfig service = new TomcatServiceConfig();
		service.setName("Portal");
		service.setAddress("192.168.10.20");
		service.setPort(8080);
		service.setDocBase("D:\\Work_Java\\Portal\\web");
		serverXmlService.patchLegacyIpFragment(INSTANCE_ID, "Portal", service);

		String patched = Files.readString(fragments.resolve("Portal.xml"));
		assertTrue(patched.contains("address=\"192.168.10.20\""));
		assertTrue(patched.contains("port=\"8080\""));
		assertTrue(patched.contains("redirectPort=\"443\""));
		assertFalse(patched.contains("port=\"36\""));
	}

	@Test
	void mergeImportedServicesKeepsImportedDocBaseWhenPreviousNull() {
		TomcatServiceConfig imported = new TomcatServiceConfig();
		imported.setName("Portal");
		imported.setType(TomcatServiceType.LEGACY_IP);
		imported.setDocBase("D:\\Work_Java\\Portal\\web");

		TomcatServiceConfig previous = new TomcatServiceConfig();
		previous.setName("Portal");
		previous.setType(TomcatServiceType.LEGACY_IP);
		previous.setDocBase(null);
		previous.setEnabled(false);
		previous.setDisplayName("公司個人系統開發平台(Portal)");

		Map<String, TomcatServiceConfig> existing = new LinkedHashMap<>();
		existing.put("Portal", previous);

		Map<String, TomcatServiceConfig> merged = serverXmlService.mergeImportedServices(
				List.of(imported), existing);
		assertEquals("D:\\Work_Java\\Portal\\web", merged.get("Portal").getDocBase());
		assertFalse(merged.get("Portal").isEnabled());
	}

}
