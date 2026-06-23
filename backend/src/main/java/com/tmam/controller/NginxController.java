package com.tmam.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tmam.dto.NginxStatusView;
import com.tmam.service.TomcatManagementService;

@RestController
@RequestMapping("/api/nginx")
public class NginxController {

	private final TomcatManagementService tomcatManagementService;

	public NginxController(TomcatManagementService tomcatManagementService) {
		this.tomcatManagementService = tomcatManagementService;
	}

	@GetMapping("/status")
	public NginxStatusView status() throws Exception {
		return tomcatManagementService.nginxStatus();
	}

	@PostMapping("/apply")
	public ResponseEntity<NginxStatusView> apply() throws Exception {
		tomcatManagementService.applyNginx();
		return ResponseEntity.ok(tomcatManagementService.nginxStatus());
	}

}
