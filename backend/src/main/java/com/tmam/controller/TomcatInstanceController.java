package com.tmam.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tmam.dto.TomcatDiscoveryView;
import com.tmam.dto.TomcatInstanceCreateRequest;
import com.tmam.dto.TomcatInstanceUpdateRequest;
import com.tmam.dto.TomcatInstanceView;
import com.tmam.dto.TomcatServiceCreateRequest;
import com.tmam.dto.TomcatServiceUpdateRequest;
import com.tmam.dto.TomcatServiceView;
import com.tmam.dto.TomcatStatusView;
import com.tmam.model.InstanceStatus;
import com.tmam.model.StartResult;
import com.tmam.model.TomcatServiceConfig;
import com.tmam.service.TomcatInstanceManagementService;

@RestController
@RequestMapping("/api/tomcats")
public class TomcatInstanceController {

	private static final Logger log = LoggerFactory.getLogger(TomcatInstanceController.class);

	private final TomcatInstanceManagementService instanceManagementService;

	public TomcatInstanceController(TomcatInstanceManagementService instanceManagementService) {
		this.instanceManagementService = instanceManagementService;
	}

	@GetMapping
	public List<TomcatInstanceView> list() throws Exception {
		return instanceManagementService.listInstances();
	}

	@GetMapping("/discover")
	public List<TomcatDiscoveryView> discover() throws Exception {
		return instanceManagementService.discover();
	}

	@GetMapping("/status")
	public Map<String, InstanceStatus> allStatus() throws Exception {
		return instanceManagementService.allInstanceStatus();
	}

	@PostMapping
	public ResponseEntity<TomcatInstanceView> create(@RequestBody TomcatInstanceCreateRequest request)
			throws Exception {
		return ResponseEntity.ok(instanceManagementService.createInstance(request));
	}

	@GetMapping("/{id}")
	public TomcatInstanceView get(@PathVariable String id) throws Exception {
		return instanceManagementService.getInstance(id);
	}

	@PutMapping("/{id}")
	public TomcatInstanceView update(@PathVariable String id, @RequestBody TomcatInstanceUpdateRequest request)
			throws Exception {
		return instanceManagementService.updateInstance(id, request);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable String id) throws Exception {
		instanceManagementService.deleteInstance(id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{id}/services")
	public List<TomcatServiceView> services(@PathVariable String id) throws Exception {
		return instanceManagementService.listServices(id);
	}

	@PostMapping("/{id}/services")
	public ResponseEntity<TomcatServiceView> createService(@PathVariable String id,
			@RequestBody TomcatServiceCreateRequest request) throws Exception {
		return ResponseEntity.ok(instanceManagementService.addService(id, request));
	}

	@PutMapping("/{id}/services/{name}")
	public TomcatServiceView updateService(@PathVariable String id, @PathVariable String name,
			@RequestBody TomcatServiceUpdateRequest request) throws Exception {
		return instanceManagementService.updateService(id, name, request);
	}

	@DeleteMapping("/{id}/services/{name}")
	public ResponseEntity<Void> deleteService(@PathVariable String id, @PathVariable String name) throws Exception {
		instanceManagementService.deleteService(id, name);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{id}/status")
	public TomcatStatusView status(@PathVariable String id) throws Exception {
		return instanceManagementService.tomcatStatus(id);
	}

	@PutMapping("/{id}/services/enabled")
	public ResponseEntity<Void> updateEnabled(@PathVariable String id,
			@RequestBody Map<String, Boolean> enabledByName) throws Exception {
		log.info("API PUT /tomcats/{}/services/enabled 收到 {} 個 Service 勾選狀態", id, enabledByName.size());
		instanceManagementService.updateEnabled(id, enabledByName);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/start")
	public ResponseEntity<StartResult> start(@PathVariable String id) throws Exception {
		StartResult result = instanceManagementService.start(id);
		return result.success() ? ResponseEntity.ok(result) : ResponseEntity.status(500).body(result);
	}

	@PostMapping("/{id}/stop")
	public ResponseEntity<Void> stop(@PathVariable String id) throws Exception {
		instanceManagementService.stop(id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/restart")
	public ResponseEntity<StartResult> restart(@PathVariable String id) throws Exception {
		StartResult result = instanceManagementService.restart(id);
		return result.success() ? ResponseEntity.ok(result) : ResponseEntity.status(500).body(result);
	}

	@PostMapping("/{id}/apply")
	public ResponseEntity<StartResult> apply(@PathVariable String id) throws Exception {
		log.info("API POST /tomcats/{}/apply", id);
		StartResult result = instanceManagementService.applyAndStart(id);
		return result.success() ? ResponseEntity.ok(result) : ResponseEntity.status(500).body(result);
	}

	@PostMapping("/{id}/services/{name}/toggle")
	public ResponseEntity<StartResult> toggle(@PathVariable String id, @PathVariable String name) throws Exception {
		StartResult result = instanceManagementService.toggleService(id, name);
		return result.success() ? ResponseEntity.ok(result) : ResponseEntity.status(500).body(result);
	}

	@PostMapping("/{id}/import")
	public List<TomcatServiceConfig> importServices(@PathVariable String id) throws Exception {
		return instanceManagementService.importServices(id);
	}

	@PostMapping("/{id}/restore-original")
	public ResponseEntity<Void> restoreOriginal(@PathVariable String id) throws Exception {
		instanceManagementService.restoreOriginal(id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{id}/logs")
	public List<String> logs(@PathVariable String id, @RequestParam(defaultValue = "100") int lines) throws Exception {
		return instanceManagementService.logs(id, lines);
	}

}
