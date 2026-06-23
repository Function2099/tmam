package com.tmam.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class InstanceControllerTest {

	private static final Path TEST_ROOT = createTestRoot();

	@Autowired
	private MockMvc mockMvc;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("tmam.config-path", () -> TEST_ROOT.resolve("projects.json").toString());
		registry.add("tmam.instances-root", () -> TEST_ROOT.resolve("instances").toString());
		registry.add("tmam.pids-root", () -> TEST_ROOT.resolve("pids").toString());
	}

	private static Path createTestRoot() {
		try {
			return Files.createTempDirectory("tmam-mvc-test");
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void startReturnsNotFoundForMissingProject() throws Exception {
		mockMvc.perform(post("/api/instances/unknown-project/start"))
				.andExpect(status().isNotFound());
	}

	@Test
	void allStatusReturnsMap() throws Exception {
		mockMvc.perform(get("/api/instances/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isMap());
	}

	@Test
	void createProjectAndList() throws Exception {
		String body = """
				{
				  "name": "mock-project",
				  "displayName": "Mock",
				  "ports": { "http": 19081, "shutdown": 19005, "ajp": 19009 }
				}
				""";

		mockMvc.perform(post("/api/projects")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.displayName").value("Mock"));

		mockMvc.perform(get("/api/projects"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].displayName").value("Mock"));
	}

}
