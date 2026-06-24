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

import com.tmam.dto.TomcatServiceCreateRequest;
import com.tmam.dto.TomcatServiceUpdateRequest;
import com.tmam.dto.TomcatServiceView;
import com.tmam.dto.TomcatStatusView;
import com.tmam.model.StartResult;
import com.tmam.model.TomcatServiceConfig;
import com.tmam.service.AppLogService;
import com.tmam.service.TomcatManagementService;

@RestController
@RequestMapping("/api/tomcat")
public class TomcatController {

	private static final Logger log = LoggerFactory.getLogger(TomcatController.class);

	private final TomcatManagementService tomcatManagementService;
	private final AppLogService appLogService;

	public TomcatController(TomcatManagementService tomcatManagementService, AppLogService appLogService) {
		this.tomcatManagementService = tomcatManagementService;
		this.appLogService = appLogService;
	}

	@GetMapping("/meta")
	public Map<String, Object> meta() throws Exception {
		tomcatManagementService.ensureReady();
		return Map.of("mode", "native", "multiTomcat", true);
	}

	@GetMapping("/services")
	public List<TomcatServiceView> services() throws Exception {
		return tomcatManagementService.listServices();
	}

	@PostMapping("/services")
	public ResponseEntity<TomcatServiceView> createService(@RequestBody TomcatServiceCreateRequest request)
			throws Exception {
		return ResponseEntity.ok(tomcatManagementService.addPathProxyService(request));
	}

	@PutMapping("/services/{name}")
	public ResponseEntity<TomcatServiceView> updateService(@PathVariable String name,
			@RequestBody TomcatServiceUpdateRequest request) throws Exception {
		return ResponseEntity.ok(tomcatManagementService.updatePathProxyService(name, request));
	}

	@DeleteMapping("/services/{name}")
	public ResponseEntity<Void> deleteService(@PathVariable String name) throws Exception {
		tomcatManagementService.deletePathProxyService(name);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/status")
	public TomcatStatusView status() throws Exception {
		return tomcatManagementService.tomcatStatus();
	}

	@PutMapping("/services/enabled")
	public ResponseEntity<Void> updateEnabled(@RequestBody Map<String, Boolean> enabledByName) throws Exception {
		log.info("API PUT /services/enabled: {}", enabledByName);
		tomcatManagementService.updateEnabled(enabledByName);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/start")
	public ResponseEntity<StartResult> start() throws Exception {
		StartResult result = tomcatManagementService.start();
		return result.success() ? ResponseEntity.ok(result) : ResponseEntity.status(500).body(result);
	}

	@PostMapping("/stop")
	public ResponseEntity<Void> stop() throws Exception {
		tomcatManagementService.stop();
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/restart")
	public ResponseEntity<StartResult> restart() throws Exception {
		StartResult result = tomcatManagementService.restart();
		return result.success() ? ResponseEntity.ok(result) : ResponseEntity.status(500).body(result);
	}

	@PostMapping("/apply")
	public ResponseEntity<StartResult> applyAndStart() throws Exception {
		log.info("API POST /apply 收到請求");
		StartResult result = tomcatManagementService.applyAndStart();
		if (result.success()) {
			log.info("API POST /apply 成功");
			return ResponseEntity.ok(result);
		}
		log.error("API POST /apply 失敗: {}", result.message());
		return ResponseEntity.status(500).body(result);
	}

	@PostMapping("/services/{name}/toggle")
	public ResponseEntity<StartResult> toggle(@PathVariable String name) throws Exception {
		StartResult result = tomcatManagementService.toggleService(name);
		return result.success() ? ResponseEntity.ok(result) : ResponseEntity.status(500).body(result);
	}

	@PostMapping("/import")
	public List<TomcatServiceConfig> importServices() throws Exception {
		return tomcatManagementService.importServices();
	}

	@PostMapping("/restore-original")
	public ResponseEntity<Void> restoreOriginal() throws Exception {
		tomcatManagementService.restoreOriginal();
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/logs")
	public List<String> logs(@RequestParam(defaultValue = "100") int lines) throws Exception {
		return tomcatManagementService.logs(lines);
	}

	@GetMapping("/app-logs")
	public List<String> appLogs(@RequestParam(defaultValue = "200") int lines) throws Exception {
		return appLogService.getLastLines(lines);
	}

}
