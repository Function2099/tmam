package com.tmam.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tmam.model.PortConfig;
import com.tmam.model.PortConflict;
import com.tmam.model.ProjectConfig;
import com.tmam.model.TmamConfig;
import com.tmam.model.TomcatInstanceConfig;
import com.tmam.model.TomcatServiceConfig;
import com.tmam.util.CatalinaHomeNormalizer;

@Service
public class ConfigService {

	public static final int GATEWAY_PORT_BASE = 8080;
	public static final int SHUTDOWN_PORT_BASE = 8005;

	@Value("${tmam.config-path}")
	private String configPath;

	@Value("${tmam.mode:native}")
	private String mode;

	private final ServerXmlService serverXmlService;
	private final ConfigMigrationService configMigrationService;

	private final ObjectMapper mapper = new ObjectMapper();

	public ConfigService(ServerXmlService serverXmlService, ConfigMigrationService configMigrationService) {
		this.serverXmlService = serverXmlService;
		this.configMigrationService = configMigrationService;
	}

	public TmamConfig load() throws IOException {
		Path path = Path.of(configPath);
		TmamConfig config;
		if (!Files.exists(path)) {
			config = createDefaultConfig();
		}
		else {
			config = mapper.readValue(path.toFile(), TmamConfig.class);
		}
		normalize(config);
		boolean migrated = configMigrationService.migrateIfNeeded(config);
		boolean deduped = dedupeTomcatInstancesByCatalinaHome(config);
		if (migrated || deduped || !Files.exists(path)) {
			save(config);
		}
		return config;
	}

	public void save(TmamConfig config) throws IOException {
		Path path = Path.of(configPath);
		Files.createDirectories(path.getParent());
		normalize(config);
		mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), config);
	}

	public List<PortConflict> detectPortConflicts(TmamConfig config) {
		Map<String, String> registry = new HashMap<>();
		List<PortConflict> conflicts = new ArrayList<>();

		config.getProjects().forEach((name, project) -> {
			if (!project.isEnabled()) {
				return;
			}
			PortConfig ports = project.getPorts();
			if (ports == null) {
				return;
			}
			checkPortKey(ports.http(), "HTTP", name, registry, conflicts);
			checkPortKey(ports.shutdown(), "Shutdown", name, registry, conflicts);
			checkPortKey(ports.ajp(), "AJP", name, registry, conflicts);
		});

		for (Map.Entry<String, TomcatInstanceConfig> entry : config.getTomcatInstances().entrySet()) {
			String instanceId = entry.getKey();
			TomcatInstanceConfig instance = entry.getValue();
			checkPortKey(instance.getShutdownPort(), "Shutdown", instanceId, registry, conflicts);
			checkPortKey(instance.getGatewayPort(), "Gateway", instanceId, registry, conflicts);
			for (TomcatServiceConfig service : instance.getServices().values()) {
				if (!service.isEnabled() || !service.isLegacyIp()) {
					continue;
				}
				String key = service.getAddress() + ":" + service.getPort();
				if (registry.containsKey(key)) {
					conflicts.add(new PortConflict(service.getPort(), "LEGACY_IP", registry.get(key), instanceId));
				}
				else {
					registry.put(key, instanceId);
				}
			}
		}
		return conflicts;
	}

	public void ensureServicesImported(TmamConfig config, String instanceId) throws Exception {
		if (!config.isNativeMode()) {
			return;
		}
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		if (!instance.getServices().isEmpty() && serverXmlService.hasImportedFragments(instanceId)) {
			return;
		}
		List<TomcatServiceConfig> imported = serverXmlService.importFromServerXml(instanceId,
				instance.getCatalinaHome());
		instance.setServices(serverXmlService.mergeImportedServices(imported, instance.getServices()));
		instance.setShutdownPort(serverXmlService.readShutdownPortFromHeader(instanceId));
		save(config);
	}

	public int allocateGatewayPort(TmamConfig config) {
		Set<Integer> used = new HashSet<>();
		for (TomcatInstanceConfig instance : config.getTomcatInstances().values()) {
			used.add(instance.getGatewayPort());
		}
		int port = GATEWAY_PORT_BASE;
		while (used.contains(port)) {
			port++;
		}
		return port;
	}

	public int allocateShutdownPort(TmamConfig config) {
		Set<Integer> used = new HashSet<>();
		for (TomcatInstanceConfig instance : config.getTomcatInstances().values()) {
			used.add(instance.getShutdownPort());
		}
		int port = SHUTDOWN_PORT_BASE;
		while (used.contains(port)) {
			port++;
		}
		return port;
	}

	public String allocateInstanceId(TmamConfig config, String catalinaHome) {
		String base = Path.of(catalinaHome).getFileName().toString().replaceAll("[^A-Za-z0-9_\\-]", "_");
		if (!config.getTomcatInstances().containsKey(base)) {
			return base;
		}
		int suffix = 2;
		while (config.getTomcatInstances().containsKey(base + "-" + suffix)) {
			suffix++;
		}
		return base + "-" + suffix;
	}

	private TmamConfig createDefaultConfig() {
		TmamConfig config = new TmamConfig();
		config.setMode(mode);
		config.setVersion(TmamConfig.VERSION_2);
		config.setTomcatInstances(new LinkedHashMap<>());
		return config;
	}

	private void normalize(TmamConfig config) {
		if (config.getProjects() == null) {
			config.setProjects(new LinkedHashMap<>());
		}
		if (config.getServices() == null) {
			config.setServices(new LinkedHashMap<>());
		}
		if (config.getTomcatInstances() == null) {
			config.setTomcatInstances(new LinkedHashMap<>());
		}
		config.getProjects().forEach((name, project) -> project.setName(name));
		if (config.getMode() == null || config.getMode().isBlank()) {
			config.setMode(mode);
		}
		config.getTomcatInstances().forEach((id, instance) -> {
			instance.setId(id);
			if (instance.getServices() == null) {
				instance.setServices(new LinkedHashMap<>());
			}
			instance.getServices().forEach((name, service) -> service.setName(name));
			if (instance.getDisplayName() == null || instance.getDisplayName().isBlank()) {
				instance.setDisplayName(id);
			}
			if (instance.getGatewayPort() <= 0) {
				instance.setGatewayPort(GATEWAY_PORT_BASE);
			}
			if (instance.getShutdownPort() <= 0) {
				instance.setShutdownPort(SHUTDOWN_PORT_BASE);
			}
		});
	}

	boolean dedupeTomcatInstancesByCatalinaHome(TmamConfig config) {
		Map<String, TomcatInstanceConfig> instances = config.getTomcatInstances();
		Map<String, List<String>> homeKeyToIds = new LinkedHashMap<>();
		for (Map.Entry<String, TomcatInstanceConfig> entry : instances.entrySet()) {
			String homeKey = CatalinaHomeNormalizer.comparisonKey(entry.getValue().getCatalinaHome());
			homeKeyToIds.computeIfAbsent(homeKey, key -> new ArrayList<>()).add(entry.getKey());
		}

		boolean removed = false;
		for (List<String> ids : homeKeyToIds.values()) {
			if (ids.size() <= 1) {
				continue;
			}
			String keepId = ids.stream()
					.filter(TomcatInstanceConfig.DEFAULT_ID::equals)
					.findFirst()
					.orElse(ids.get(0));
			for (String id : ids) {
				if (!id.equals(keepId)) {
					instances.remove(id);
					removed = true;
				}
			}
		}
		return removed;
	}

	private void checkPortKey(int port, String type, String owner, Map<String, String> registry,
			List<PortConflict> conflicts) {
		String key = "port:" + port;
		if (registry.containsKey(key)) {
			conflicts.add(new PortConflict(port, type, registry.get(key), owner));
		}
		else {
			registry.put(key, owner);
		}
	}

}
