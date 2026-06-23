package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tmam.model.TmamConfig;
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
		TmamConfig config = new TmamConfig();
		TomcatServiceConfig service = new TomcatServiceConfig();
		service.setName("New_System");
		service.setType(TomcatServiceType.PATH_PROXY);
		service.setPathPrefix("/new-system");
		service.setEnabled(true);
		config.getServices().put(service.getName(), service);

		String locations = nginxConfigService.buildLocationsFragment(config);
		assertTrue(locations.contains("location /new-system/"));
		assertTrue(locations.contains("proxy_pass http://127.0.0.1:8080/new-system/"));
	}

	@Test
	void writeConfigCreatesMainAndLocationFiles() throws Exception {
		TmamConfig config = new TmamConfig();
		TomcatServiceConfig service = new TomcatServiceConfig();
		service.setName("New_System");
		service.setType(TomcatServiceType.PATH_PROXY);
		service.setPathPrefix("/portal");
		service.setEnabled(true);
		config.getServices().put(service.getName(), service);

		nginxConfigService.writeConfig(config);

		assertTrue(Files.exists(nginxConfigService.getLocationsFragment()));
		assertTrue(Files.exists(nginxConfigService.getConfigPath()));
		String mainConfig = Files.readString(nginxConfigService.getConfigPath());
		assertTrue(mainConfig.contains("listen 80"));
		assertTrue(mainConfig.contains("mime.types") || mainConfig.contains("conf/mime.types"));
	}

	@Test
	void isAvailableFalseWhenExecutableMissing() {
		assertFalse(nginxConfigService.isAvailable());
	}

}
