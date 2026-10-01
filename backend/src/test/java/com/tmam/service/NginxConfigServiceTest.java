package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tmam.model.TmamConfig;
import com.tmam.model.TomcatInstanceConfig;
import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;

class NginxConfigServiceTest {

	@TempDir
	Path tempDir;

	private NginxConfigService nginxConfigService;

	@BeforeEach
	void setUp() {
		nginxConfigService = NginxConfigService.forTest(tempDir);
	}

	@Test
	void buildLocationsFragmentForEnabledPathProxy() throws Exception {
		TmamConfig config = configWithPathProxy("/new-system", 8080);

		String locations = nginxConfigService.buildLocationsFragment(config);
		assertTrue(locations.contains("location /new-system/"));
		assertTrue(locations.contains("proxy_pass http://127.0.0.1:8080/new-system/;"));
		assertTrue(locations.contains("proxy_hide_header Strict-Transport-Security;"));
		assertTrue(locations.contains("add_header Set-Cookie \"tmam_ctx=/new-system; Path=/; SameSite=Lax\" always;"));
		assertFalse(locations.contains("sub_filter"));
	}

	@Test
	void buildLocationsFragmentUsesInstanceGatewayPort() throws Exception {
		TmamConfig config = configWithPathProxy("/portal", 8090);

		String locations = nginxConfigService.buildLocationsFragment(config);
		assertTrue(locations.contains("proxy_pass http://127.0.0.1:8090/portal/"));
	}

	@Test
	void buildLocationsFragmentAddsRedirectForBarePrefix() throws Exception {
		TmamConfig config = configWithPathProxy("/portal", 8080);

		String locations = nginxConfigService.buildLocationsFragment(config);
		assertTrue(locations.contains("location = /portal {"));
		assertTrue(locations.contains("return 301 /portal/;"));
	}

	@Test
	void buildLocationsFragmentAddsIndexPageRedirect() throws Exception {
		TmamConfig config = configWithPathProxy("/clbu_leeten", 8080);
		TomcatServiceConfig service = config.getTomcatInstances()
				.get(TomcatInstanceConfig.DEFAULT_ID).getServices().get("New_System");
		service.setIndexPage("index_Login.jsp");

		String locations = nginxConfigService.buildLocationsFragment(config);
		assertTrue(locations.contains("location = /clbu_leeten/ {"));
		assertTrue(locations.contains("return 302 /clbu_leeten/index_Login.jsp$is_args$args;"));
	}

	@Test
	void buildLocationsFragmentAddsExactLocationForFileLegacyPath() throws Exception {
		TmamConfig config = configWithPathProxy("/CloudPortal", 8080);
		TomcatServiceConfig service = config.getTomcatInstances()
				.get(TomcatInstanceConfig.DEFAULT_ID).getServices().get("New_System");
		service.setLegacyPaths(java.util.List.of("/Portal.jsp"));

		String locations = nginxConfigService.buildLocationsFragment(config);
		assertTrue(locations.contains("location = /Portal.jsp {"));
		assertTrue(locations.contains("return 302 $tmam_ctx$request_uri;"));
		assertTrue(locations.contains("add_header Set-Cookie \"tmam_ctx=/CloudPortal; Path=/; SameSite=Lax\" always;"));
		assertFalse(locations.contains("location /Portal.jsp/ {"));
		assertFalse(locations.contains("proxy_pass http://127.0.0.1:8080/CloudPortal/Portal.jsp;"));
	}

	@Test
	void buildLocationsFragmentAddsLegacyPathLocations() throws Exception {
		TmamConfig config = configWithPathProxy("/clbu_leeten", 8080);
		TomcatServiceConfig service = config.getTomcatInstances()
				.get(TomcatInstanceConfig.DEFAULT_ID).getServices().get("New_System");
		service.setLegacyPaths(java.util.List.of("/images", "/Modules"));

		String locations = nginxConfigService.buildLocationsFragment(config);
		assertTrue(locations.contains("location /images/ {"));
		assertTrue(locations.contains("location /Modules/ {"));
		assertTrue(locations.contains("return 302 $tmam_ctx$request_uri;"));
		assertFalse(locations.contains("proxy_pass http://127.0.0.1:8080/clbu_leeten/images/;"));
	}

	@Test
	void buildLocationsFragmentLegacyPathsRespectStripPrefix() throws Exception {
		TmamConfig config = configWithPathProxy("/portal", 8080);
		TomcatServiceConfig service = config.getTomcatInstances()
				.get(TomcatInstanceConfig.DEFAULT_ID).getServices().get("New_System");
		service.setProxyStripPrefix(true);
		service.setLegacyPaths(java.util.List.of("/images"));

		String locations = nginxConfigService.buildLocationsFragment(config);
		assertTrue(locations.contains("proxy_pass http://127.0.0.1:8080/;"));
		assertTrue(locations.contains("return 302 $tmam_ctx$request_uri;"));
		assertFalse(locations.contains("sub_filter"));
	}

	@Test
	void buildLocationsFragmentDedupesSharedLegacyPaths() throws Exception {
		TmamConfig config = configWithPathProxy("/Web", 8080);
		TomcatInstanceConfig instance = config.getTomcatInstances().get(TomcatInstanceConfig.DEFAULT_ID);
		TomcatServiceConfig frontend = instance.getServices().get("New_System");
		frontend.setLegacyPaths(java.util.List.of("/images", "/Modules"));

		TomcatServiceConfig backend = new TomcatServiceConfig();
		backend.setName("Mgr");
		backend.setType(TomcatServiceType.PATH_PROXY);
		backend.setPathPrefix("/mgr");
		backend.setEnabled(true);
		backend.setLegacyPaths(java.util.List.of("/images", "/Template"));
		instance.getServices().put(backend.getName(), backend);

		String locations = nginxConfigService.buildLocationsFragment(config);
		assertTrue(locations.contains("return 302 $tmam_ctx$request_uri;"));
		assertTrue(locations.contains("location /Modules/ {"));
		assertTrue(locations.contains("location /Template/ {"));
		assertFalse(locations.contains("proxy_pass http://127.0.0.1:8080/mgr/Template/;"));
		assertFalse(locations.contains("proxy_pass $tmam_legacy_upstream$request_uri;"));
		assertEquals(1, locations.split("location /images/ \\{", -1).length - 1);
	}

	@Test
	void buildLocationsFragmentSkipsCaseInsensitiveLegacyPathOnWindows() throws Exception {
		TmamConfig config = configWithPathProxy("/clbu_leeten", 8080);
		TomcatInstanceConfig instance = config.getTomcatInstances().get(TomcatInstanceConfig.DEFAULT_ID);
		TomcatServiceConfig house = instance.getServices().get("New_System");
		house.setLegacyPaths(java.util.List.of("/error"));

		TomcatServiceConfig cloud = new TomcatServiceConfig();
		cloud.setName("CloudPortal");
		cloud.setType(TomcatServiceType.PATH_PROXY);
		cloud.setPathPrefix("/CloudPortal");
		cloud.setEnabled(true);
		cloud.setLegacyPaths(java.util.List.of("/Error"));
		instance.getServices().put(cloud.getName(), cloud);

		String locations = nginxConfigService.buildLocationsFragment(config);
		assertTrue(locations.contains("return 302 $tmam_ctx$request_uri;"));
		assertEquals(1, locations.split("location /error/ \\{", -1).length
				+ locations.split("location /Error/ \\{", -1).length - 2);
		assertFalse(locations.contains("proxy_pass http://127.0.0.1:8080/CloudPortal/Error/;"));
		assertFalse(locations.contains("proxy_pass http://127.0.0.1:8080/clbu_leeten/error/;"));
	}

	@Test
	void writeConfigCreatesMainAndLocationFiles() throws Exception {
		TmamConfig config = configWithPathProxy("/portal", 8080);
		nginxConfigService.writeConfig(config);

		assertTrue(Files.exists(nginxConfigService.getLocationsFragment()));
		assertTrue(Files.exists(nginxConfigService.getConfigPath()));
		String mainConfig = Files.readString(nginxConfigService.getConfigPath());
		assertTrue(mainConfig.contains("listen 80"));
		assertTrue(mainConfig.contains("map $http_referer $tmam_ctx_from_referer"));
		assertTrue(mainConfig.contains("map $cookie_tmam_ctx $tmam_ctx"));
		assertTrue(mainConfig.contains("~*/portal(/|\\?|$)"));
		assertTrue(mainConfig.contains("        /portal /portal;"));
		assertTrue(mainConfig.contains("location / {"));
		assertFalse(mainConfig.contains("root   html"));
		assertTrue(mainConfig.contains("pid "));
	}

	@Test
	void isAvailableFalseWhenExecutableMissing() {
		assertFalse(nginxConfigService.isAvailable());
	}

	@Test
	void isListeningFalseWhenPortClosed() {
		NginxConfigService unusedPort = new NginxConfigService(
				true,
				tempDir.resolve("missing-nginx.exe").toString(),
				tempDir.resolve("nginx/nginx.conf").toString(),
				tempDir.resolve("nginx/tmam-locations.conf").toString(),
				59999,
				"127.0.0.1",
				new NginxDiscoveryService());
		assertFalse(unusedPort.isListening());
	}

	private TmamConfig configWithPathProxy(String pathPrefix, int gatewayPort) {
		TmamConfig config = new TmamConfig();
		TomcatInstanceConfig instance = new TomcatInstanceConfig();
		instance.setId(TomcatInstanceConfig.DEFAULT_ID);
		instance.setGatewayPort(gatewayPort);
		TomcatServiceConfig service = new TomcatServiceConfig();
		service.setName("New_System");
		service.setType(TomcatServiceType.PATH_PROXY);
		service.setPathPrefix(pathPrefix);
		service.setEnabled(true);
		instance.getServices().put(service.getName(), service);
		Map<String, TomcatInstanceConfig> instances = new LinkedHashMap<>();
		instances.put(TomcatInstanceConfig.DEFAULT_ID, instance);
		config.setTomcatInstances(instances);
		return config;
	}

}
