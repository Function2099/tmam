package com.tmam.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tmam.model.PortConfig;
import com.tmam.model.PortConflict;
import com.tmam.model.ProjectConfig;
import com.tmam.model.TmamConfig;
import com.tmam.model.TomcatServiceConfig;

@Service
public class ConfigService {

	@Value("${tmam.config-path}")
	private String configPath;

	@Value("${tmam.default-catalina-home}")
	private String defaultCatalinaHome;

	@Value("${tmam.mode:native}")
	private String mode;

	private final ServerXmlService serverXmlService;

	private final ObjectMapper mapper = new ObjectMapper();

	public ConfigService(ServerXmlService serverXmlService) {
		this.serverXmlService = serverXmlService;
	}

	public TmamConfig load() throws IOException {
		Path path = Path.of(configPath);
		TmamConfig config;
		if (!Files.exists(path)) {
			config = createDefaultConfig();
			save(config);
		}
		else {
			config = mapper.readValue(path.toFile(), TmamConfig.class);
		}
		normalize(config);
		return config;
	}

	public void save(TmamConfig config) throws IOException {
		Path path = Path.of(configPath);
		Files.createDirectories(path.getParent());
		mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), config);
	}

	public List<PortConflict> detectPortConflicts(TmamConfig config) {
		Map<Integer, String> registry = new HashMap<>();
		List<PortConflict> conflicts = new ArrayList<>();

		config.getProjects().forEach((name, project) -> {
			if (!project.isEnabled()) {
				return;
			}
			PortConfig ports = project.getPorts();
			if (ports == null) {
				return;
			}
			checkPort(ports.http(), "HTTP", name, registry, conflicts);
			checkPort(ports.shutdown(), "Shutdown", name, registry, conflicts);
			checkPort(ports.ajp(), "AJP", name, registry, conflicts);
		});
		return conflicts;
	}

	private TmamConfig createDefaultConfig() {
		TmamConfig config = new TmamConfig();
		config.setMode(mode);
		config.setCatalinaHome(defaultCatalinaHome);
		return config;
	}

	private void normalize(TmamConfig config) {
		config.getProjects().forEach((name, project) -> project.setName(name));
		if (config.getCatalinaHome() == null || config.getCatalinaHome().isBlank()) {
			config.setCatalinaHome(defaultCatalinaHome);
		}
		if (config.getMode() == null || config.getMode().isBlank()) {
			config.setMode(mode);
		}
		config.getServices().forEach((name, service) -> service.setName(name));
	}

	public void ensureServicesImported(TmamConfig config) throws Exception {
		if (!config.isNativeMode()) {
			return;
		}
		if (!config.getServices().isEmpty() && serverXmlService.hasImportedFragments()) {
			return;
		}
		List<TomcatServiceConfig> imported = serverXmlService.importFromServerXml(config.getCatalinaHome());
		config.setServices(serverXmlService.mergeImportedServices(imported, config.getServices()));
		save(config);
	}

	private void checkPort(int port, String type, String projectName, Map<Integer, String> registry,
			List<PortConflict> conflicts) {
		if (registry.containsKey(port)) {
			conflicts.add(new PortConflict(port, type, registry.get(port), projectName));
		}
		else {
			registry.put(port, projectName);
		}
	}

}
