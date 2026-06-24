package com.tmam.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tmam.model.TmamConfig;
import com.tmam.model.TomcatInstanceConfig;
import com.tmam.model.TomcatServiceConfig;

@Service
public class ConfigMigrationService {

	private static final Logger log = LoggerFactory.getLogger(ConfigMigrationService.class);

	private final String configPath;
	private final String instancesRoot;
	private final String legacyFragmentsRoot;
	private final String legacyNativeBase;
	private final String defaultCatalinaHome;

	public ConfigMigrationService(
			@Value("${tmam.config-path}") String configPath,
			@Value("${tmam.instances-root}") String instancesRoot,
			@Value("${tmam.server-xml-fragments}") String legacyFragmentsRoot,
			@Value("${tmam.native-catalina-base}") String legacyNativeBase,
			@Value("${tmam.default-catalina-home}") String defaultCatalinaHome) {
		this.configPath = configPath;
		this.instancesRoot = instancesRoot;
		this.legacyFragmentsRoot = legacyFragmentsRoot;
		this.legacyNativeBase = legacyNativeBase;
		this.defaultCatalinaHome = defaultCatalinaHome;
	}

	public boolean migrateIfNeeded(TmamConfig config) throws IOException {
		if (config.isV2()) {
			return false;
		}
		log.info("[migrateIfNeeded] 開始 v1 → v2 遷移");

		Path configFile = Path.of(configPath);
		if (Files.exists(configFile)) {
			Files.copy(configFile, configFile.resolveSibling("projects.json.v1.bak"),
					StandardCopyOption.REPLACE_EXISTING);
			log.info("[migrateIfNeeded] 已備份設定檔為 projects.json.v1.bak");
		}

		TomcatInstanceConfig defaultInstance = new TomcatInstanceConfig();
		defaultInstance.setId(TomcatInstanceConfig.DEFAULT_ID);
		String home = config.getCatalinaHome();
		if (home == null || home.isBlank()) {
			home = defaultCatalinaHome;
		}
		defaultInstance.setCatalinaHome(home);
		defaultInstance.setDisplayName(Path.of(home).getFileName().toString());
		defaultInstance.setShutdownPort(8005);
		defaultInstance.setGatewayPort(8080);

		Map<String, TomcatServiceConfig> services = config.getServices();
		if (services != null && !services.isEmpty()) {
			defaultInstance.setServices(new LinkedHashMap<>(services));
		}
		else {
			defaultInstance.setServices(new LinkedHashMap<>());
		}

		Map<String, TomcatInstanceConfig> instances = new LinkedHashMap<>();
		instances.put(TomcatInstanceConfig.DEFAULT_ID, defaultInstance);
		config.setTomcatInstances(instances);
		config.setVersion(TmamConfig.VERSION_2);
		config.setCatalinaHome(null);
		config.setServices(new LinkedHashMap<>());

		migrateFilesystem(TomcatInstanceConfig.DEFAULT_ID);
		log.info("[migrateIfNeeded] 遷移完成，default 實例 catalinaHome={}", home);
		return true;
	}

	private void migrateFilesystem(String instanceId) throws IOException {
		Path instanceRoot = Path.of(instancesRoot, instanceId);
		Path targetFragments = instanceRoot.resolve("server-fragments");
		Path targetBase = instanceRoot.resolve("catalina-base");

		Path legacyFragments = Path.of(legacyFragmentsRoot);
		if (Files.exists(legacyFragments)) {
			copyDirectory(legacyFragments, targetFragments);
			log.info("[migrateFilesystem] 已搬移 fragments {} -> {}", legacyFragments, targetFragments);
		}

		Path legacyBase = Path.of(legacyNativeBase);
		if (Files.exists(legacyBase)) {
			copyDirectory(legacyBase, targetBase);
			log.info("[migrateFilesystem] 已搬移 catalina-base {} -> {}", legacyBase, targetBase);
		}

		Files.createDirectories(targetFragments);
		Files.createDirectories(targetBase);
	}

	private void copyDirectory(Path source, Path target) throws IOException {
		if (!Files.exists(source)) {
			return;
		}
		Files.createDirectories(target);
		try (Stream<Path> walk = Files.walk(source)) {
			walk.forEach(path -> {
				try {
					Path dest = target.resolve(source.relativize(path));
					if (Files.isDirectory(path)) {
						Files.createDirectories(dest);
					}
					else {
						Files.createDirectories(dest.getParent());
						Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
					}
				}
				catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		}
	}

}
