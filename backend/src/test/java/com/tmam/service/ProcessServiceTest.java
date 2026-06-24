package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import com.tmam.model.InstanceStatus;
import com.tmam.model.PortConfig;
import com.tmam.model.ProjectConfig;

class ProcessServiceTest {

	@TempDir
	Path tempDir;

	private ProcessService processService;
	private ConfigService configService;

	@BeforeEach
	void setUp() throws IOException {
		XmlConfiguratorService xmlConfiguratorService = new XmlConfiguratorService(
				new ClassPathResource("server-template.xml"));
		NativeTomcatEnvironmentService nativeTomcatEnvironmentService = new NativeTomcatEnvironmentService(
				tempDir.resolve("instances").toString(), xmlConfiguratorService);
		ServerXmlService serverXmlService = new ServerXmlService(
				"C:/Program Files/apache-tomcat-9.0.115",
				"PathGateway",
				nativeTomcatEnvironmentService);
		ConfigMigrationService migrationService = new ConfigMigrationService(
				tempDir.resolve("projects.json").toString(),
				tempDir.resolve("instances").toString(),
				tempDir.resolve("fragments").toString(),
				tempDir.resolve("native-base").toString(),
				"C:/Program Files/apache-tomcat-9.0.115");
		configService = new ConfigService(serverXmlService, migrationService);
		ReflectionTestUtils.setField(configService, "configPath",
				tempDir.resolve("projects.json").toString());

		processService = new ProcessService(
				tempDir.resolve("instances").toString(),
				tempDir.resolve("pids").toString(),
				"C:/Program Files/apache-tomcat-9.0.115",
				30,
				configService,
				nativeTomcatEnvironmentService);
	}

	@Test
	void statusReturnsStoppedWhenPidFileMissing() {
		assertEquals(InstanceStatus.STOPPED, processService.status("missing"));
	}

	@Test
	void getLastLinesReturnsTailOfLogFile() throws IOException {
		Path logsDir = tempDir.resolve("instances/demo/logs");
		Files.createDirectories(logsDir);
		Path logFile = logsDir.resolve("catalina.2026-06-18.log");
		Files.writeString(logFile, String.join("\n",
				"line-1", "line-2", "line-3", "line-4", "line-5"));

		var lines = processService.getLastLines("demo", 3);

		assertEquals(3, lines.size());
		assertEquals("line-3", lines.get(0));
		assertEquals("line-5", lines.get(2));
	}

	@Test
	void getLastLinesReturnsEmptyWhenLogMissing() throws IOException {
		assertTrue(processService.getLastLines("demo", 10).isEmpty());
	}

}
