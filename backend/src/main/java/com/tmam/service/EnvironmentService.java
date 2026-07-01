package com.tmam.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tmam.model.ProjectConfig;

@Service
public class EnvironmentService {

	private static final List<String> INSTANCE_DIRS = List.of("conf", "logs", "temp", "work", "webapps");

	private final String instancesRoot;
	private final CatalinaHomeResolver catalinaHomeResolver;
	private final XmlConfiguratorService xmlConfiguratorService;

	public EnvironmentService(@Value("${tmam.instances-root}") String instancesRoot,
			CatalinaHomeResolver catalinaHomeResolver,
			XmlConfiguratorService xmlConfiguratorService) {
		this.instancesRoot = instancesRoot;
		this.catalinaHomeResolver = catalinaHomeResolver;
		this.xmlConfiguratorService = xmlConfiguratorService;
	}

	public Path getInstanceBase(String projectName) {
		return Path.of(instancesRoot, projectName);
	}

	public void initialize(ProjectConfig project) throws Exception {
		Path catalinaBase = getInstanceBase(project.getName());
		Path catalinaHome = Path.of(resolveCatalinaHome(project));

		for (String dir : INSTANCE_DIRS) {
			Files.createDirectories(catalinaBase.resolve(dir));
		}

		xmlConfiguratorService.copyFromHome(catalinaHome, catalinaBase);
		xmlConfiguratorService.generate(project, catalinaBase.resolve("conf/server.xml"));
		deployWar(project, catalinaBase);
	}

	private String resolveCatalinaHome(ProjectConfig project) {
		return catalinaHomeResolver.resolve(project.getCatalinaHome());
	}

	private void deployWar(ProjectConfig project, Path catalinaBase) throws IOException {
		String warPath = project.getWarPath();
		if (warPath == null || warPath.isBlank()) {
			return;
		}

		Path war = Path.of(warPath);
		if (!Files.exists(war)) {
			throw new IOException("WAR not found: " + war);
		}

		String contextPath = project.getContextPath();
		Path destination;
		if (contextPath != null && "ROOT".equalsIgnoreCase(contextPath)) {
			destination = catalinaBase.resolve("webapps/ROOT.war");
		}
		else if (contextPath != null && !contextPath.isBlank()) {
			destination = catalinaBase.resolve("webapps/" + contextPath + ".war");
		}
		else {
			destination = catalinaBase.resolve("webapps").resolve(war.getFileName());
		}

		Files.copy(war, destination, StandardCopyOption.REPLACE_EXISTING);
	}

}
