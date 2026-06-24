package com.tmam.service;

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
		assertTrue(locations.contains("proxy_pass http://127.0.0.1:8080/new-system/"));
	}

	@Test
	void buildLocationsFragmentUsesInstanceGatewayPort() throws Exception {
		TmamConfig config = configWithPathProxy("/portal", 8090);

		String locations = nginxConfigService.buildLocationsFragment(config);
		assertTrue(locations.contains("proxy_pass http://127.0.0.1:8090/portal/"));
	}

	@Test
	void writeConfigCreatesMainAndLocationFiles() throws Exception {
		TmamConfig config = configWithPathProxy("/portal", 8080);
		nginxConfigService.writeConfig(config);

		assertTrue(Files.exists(nginxConfigService.getLocationsFragment()));
		assertTrue(Files.exists(nginxConfigService.getConfigPath()));
		String mainConfig = Files.readString(nginxConfigService.getConfigPath());
		assertTrue(mainConfig.contains("listen 80"));
	}

	@Test
	void isAvailableFalseWhenExecutableMissing() {
		assertFalse(nginxConfigService.isAvailable());
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
