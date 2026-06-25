package com.tmam.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.tmam.dto.TomcatServiceView;
import com.tmam.dto.TomcatStatusView;
import com.tmam.model.InstanceStatus;
import com.tmam.model.StartResult;
import com.tmam.model.TomcatServiceType;
import com.tmam.service.AppLogService;
import com.tmam.service.TomcatManagementService;

@WebMvcTest(TomcatController.class)
class TomcatControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TomcatManagementService tomcatManagementService;

	@MockitoBean
	private AppLogService appLogService;

	@Test
	void listServices() throws Exception {
		when(tomcatManagementService.listServices()).thenReturn(List.of(
				new TomcatServiceView("Portal_Area", "Portal_Area", TomcatServiceType.LEGACY_IP,
						"192.168.10.10", 36, true, InstanceStatus.RUNNING, null, null, null, false)));

		mockMvc.perform(get("/api/tomcat/services"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("Portal_Area"))
				.andExpect(jsonPath("$[0].port").value(36));
	}

	@Test
	void tomcatStatus() throws Exception {
		when(tomcatManagementService.tomcatStatus())
				.thenReturn(new TomcatStatusView(InstanceStatus.RUNNING, false));

		mockMvc.perform(get("/api/tomcat/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RUNNING"));
	}

	@Test
	void toggleService() throws Exception {
		when(tomcatManagementService.toggleService("Portal_Area"))
				.thenReturn(StartResult.success("tomcat"));

		mockMvc.perform(post("/api/tomcat/services/Portal_Area/toggle"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

		verify(tomcatManagementService).toggleService("Portal_Area");
	}

	@Test
	void createPathProxyService() throws Exception {
		when(tomcatManagementService.addPathProxyService(org.mockito.ArgumentMatchers.any()))
				.thenReturn(new TomcatServiceView("New_System", "新系統", TomcatServiceType.PATH_PROXY,
						"127.0.0.1", 80, true, InstanceStatus.STOPPED, "/new-system", "D:/web",
						"http://localhost:80/new-system/", false));

		mockMvc.perform(post("/api/tomcat/services")
				.contentType("application/json")
				.content("""
						{
						  "name": "New_System",
						  "displayName": "新系統",
						  "pathPrefix": "/new-system",
						  "docBase": "D:/web",
						  "enabled": true
						}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("New_System"))
				.andExpect(jsonPath("$.type").value("PATH_PROXY"));
	}

	@Test
	void deletePathProxyService() throws Exception {
		mockMvc.perform(delete("/api/tomcat/services/New_System"))
				.andExpect(status().isNoContent());

		verify(tomcatManagementService).deletePathProxyService("New_System");
	}

	@Test
	void applyAndStart_success() throws Exception {
		when(tomcatManagementService.applyAndStart())
				.thenReturn(StartResult.success("tomcat"));

		mockMvc.perform(post("/api/tomcat/apply"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

		verify(tomcatManagementService).applyAndStart();
	}

	@Test
	void applyAndStart_failure() throws Exception {
		when(tomcatManagementService.applyAndStart())
				.thenReturn(StartResult.failure("tomcat", "至少需要啟用一個 Service"));

		mockMvc.perform(post("/api/tomcat/apply"))
				.andExpect(status().is5xxServerError())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.message").value("至少需要啟用一個 Service"));
	}

}
