package com.tmam.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tmam.dto.ProjectCreateRequest;
import com.tmam.model.PortConflict;
import com.tmam.model.ProjectConfig;
import com.tmam.model.TmamConfig;
import com.tmam.service.ConfigService;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

	private final ConfigService configService;

	public ProjectController(ConfigService configService) {
		this.configService = configService;
	}

	@GetMapping
	public List<ProjectConfig> list() throws IOException {
		TmamConfig config = configService.load();
		List<ProjectConfig> projects = new ArrayList<>();
		config.getProjects().forEach((name, project) -> {
			project.setName(name);
			projects.add(project);
		});
		return projects;
	}

	@PostMapping
	public ProjectConfig add(@RequestBody ProjectCreateRequest request) throws IOException {
		TmamConfig config = configService.load();
		if (config.getProjects().containsKey(request.name())) {
			throw new IllegalArgumentException("專案已存在: " + request.name());
		}

		ProjectConfig project = new ProjectConfig();
		project.setName(request.name());
		project.setDisplayName(request.displayName());
		project.setPorts(request.ports());
		project.setWarPath(request.warPath());
		project.setContextPath(request.contextPath());
		project.setJvmOpts(request.jvmOpts());

		config.getProjects().put(request.name(), project);
		configService.save(config);
		return project;
	}

	@PutMapping("/{name}")
	public ResponseEntity<ProjectConfig> update(@PathVariable String name,
			@RequestBody ProjectConfig updated) throws IOException {
		TmamConfig config = configService.load();
		if (!config.getProjects().containsKey(name)) {
			return ResponseEntity.notFound().build();
		}

		updated.setName(name);
		config.getProjects().put(name, updated);
		configService.save(config);
		return ResponseEntity.ok(updated);
	}

	@DeleteMapping("/{name}")
	public ResponseEntity<Void> remove(@PathVariable String name) throws IOException {
		TmamConfig config = configService.load();
		if (config.getProjects().remove(name) == null) {
			return ResponseEntity.notFound().build();
		}
		configService.save(config);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/port-conflicts")
	public List<PortConflict> portConflicts() throws IOException {
		return configService.detectPortConflicts(configService.load());
	}

}
