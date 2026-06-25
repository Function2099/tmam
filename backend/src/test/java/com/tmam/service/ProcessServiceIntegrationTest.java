package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.tmam.model.InstanceStatus;
import com.tmam.model.PortConfig;
import com.tmam.model.ProjectConfig;
import com.tmam.model.TmamConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@EnabledOnOs(OS.WINDOWS)
class ProcessServiceIntegrationTest {

	private static final String TOMCAT_HOME = "C:/Program Files/apache-tomcat-9.0.115";
	private static final String PROJECT_NAME = "tmam-it-test";

	private static final Path testRoot = createTestRoot();

	@Autowired
	private ConfigService configService;

	@Autowired
	private EnvironmentService environmentService;

	@Autowired
	private ProcessService processService;

	@BeforeAll
	static void verifyTomcatInstalled() throws IOException {
		if (!Files.exists(Path.of(TOMCAT_HOME, "bin/catalina.bat"))) {
			throw new org.opentest4j.TestAbortedException("Tomcat not found at " + TOMCAT_HOME);
		}
	}

	@AfterAll
	static void tearDownRoot() throws IOException {
		if (testRoot != null) {
			deleteRecursively(testRoot);
		}
	}

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("tmam.config-path", () -> testRoot.resolve("projects.json").toString());
		registry.add("tmam.instances-root", () -> testRoot.resolve("instances").toString());
		registry.add("tmam.pids-root", () -> testRoot.resolve("pids").toString());
		registry.add("tmam.default-catalina-home", () -> TOMCAT_HOME);
		registry.add("tmam.startup-timeout-sec", () -> 90);
		registry.add("tmam.mode", () -> "multi-instance");
		registry.add("tmam.server-xml-fragments", () -> testRoot.resolve("fragments").toString());
	}

	@BeforeEach
	void ensureStopped() throws Exception {
		saveProject(buildProject());
		for (int attempt = 0; attempt < 3; attempt++) {
			if (processService.status(PROJECT_NAME) == InstanceStatus.STOPPED) {
				return;
			}
			processService.stop(PROJECT_NAME);
			Thread.sleep(2000);
		}
	}

	@AfterEach
	void tearDownInstance() throws Exception {
		if (processService.status(PROJECT_NAME) != InstanceStatus.STOPPED) {
			processService.stop(PROJECT_NAME);
		}
	}

	@Test
	void startAndStopRealTomcatInstance() throws Exception {
		ProjectConfig project = buildProject();
		saveProject(project);

		environmentService.initialize(project);
		var startResult = processService.start(project);

		assertTrue(startResult.success(), startResult.message());
		assertEquals(InstanceStatus.RUNNING, processService.status(PROJECT_NAME));

		processService.stop(PROJECT_NAME);
		assertEquals(InstanceStatus.STOPPED, processService.status(PROJECT_NAME));
	}

	private ProjectConfig buildProject() {
		ProjectConfig project = new ProjectConfig();
		project.setName(PROJECT_NAME);
		project.setDisplayName("TMAM Integration Test");
		project.setPorts(new PortConfig(18081, 18005, 18009));
		project.setContextPath("ROOT");
		project.setJvmOpts("-Xms128m -Xmx256m");
		return project;
	}

	private void saveProject(ProjectConfig project) throws IOException {
		TmamConfig config = new TmamConfig();
		config.setCatalinaHome(TOMCAT_HOME);
		config.getProjects().put(PROJECT_NAME, project);
		configService.save(config);
	}

	private static Path createTestRoot() {
		try {
			return Files.createTempDirectory("tmam-process-it");
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (var walk = Files.walk(root)) {
			walk.sorted((a, b) -> b.compareTo(a))
					.forEach(path -> {
						try {
							Files.deleteIfExists(path);
						}
						catch (IOException ignored) {
							// best effort cleanup
						}
					});
		}
	}

}
