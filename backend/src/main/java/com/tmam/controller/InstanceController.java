package com.tmam.controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tmam.model.InstanceStatus;
import com.tmam.model.ProjectConfig;
import com.tmam.model.StartResult;
import com.tmam.model.TmamConfig;
import com.tmam.service.ConfigService;
import com.tmam.service.EnvironmentService;
import com.tmam.service.ProcessService;

@RestController
@RequestMapping("/api/instances")
public class InstanceController {

	private final ProcessService processService;
	private final EnvironmentService environmentService;
	private final ConfigService configService;

	public InstanceController(ProcessService processService, EnvironmentService environmentService,
			ConfigService configService) {
		this.processService = processService;
		this.environmentService = environmentService;
		this.configService = configService;
	}

	@PostMapping("/{name}/start")
	public ResponseEntity<StartResult> start(@PathVariable String name) throws Exception {
		ProjectConfig project = requireProject(name);
		if (project == null) {
			return ResponseEntity.notFound().build();
		}

		environmentService.initialize(project);
		StartResult result = processService.start(project);
		return result.success()
				? ResponseEntity.ok(result)
				: ResponseEntity.status(500).body(result);
	}

	@PostMapping("/{name}/stop")
	public ResponseEntity<Void> stop(@PathVariable String name) throws Exception {
		if (requireProject(name) == null) {
			return ResponseEntity.notFound().build();
		}
		processService.stop(name);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{name}/restart")
	public ResponseEntity<StartResult> restart(@PathVariable String name) throws Exception {
		ProjectConfig project = requireProject(name);
		if (project == null) {
			return ResponseEntity.notFound().build();
		}

		environmentService.initialize(project);
		StartResult result = processService.restart(project);
		return result.success()
				? ResponseEntity.ok(result)
				: ResponseEntity.status(500).body(result);
	}

	@GetMapping("/status")
	public Map<String, InstanceStatus> allStatus() throws IOException {
		TmamConfig config = configService.load();
		Map<String, InstanceStatus> statusMap = new LinkedHashMap<>();
		config.getProjects().keySet()
				.forEach(projectName -> statusMap.put(projectName, processService.status(projectName)));
		return statusMap;
	}

	@GetMapping("/{name}/status")
	public ResponseEntity<InstanceStatus> status(@PathVariable String name) throws IOException {
		if (requireProject(name) == null) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(processService.status(name));
	}

	@GetMapping("/{name}/logs")
	public List<String> logs(@PathVariable String name, @RequestParam(defaultValue = "100") int lines)
			throws IOException {
		return processService.getLastLines(name, lines);
	}

	private ProjectConfig requireProject(String name) throws IOException {
		ProjectConfig project = configService.load().getProjects().get(name);
		if (project != null) {
			project.setName(name);
		}
		return project;
	}

}
