package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import com.tmam.model.InstanceStatus;
import com.tmam.model.PortConfig;
import com.tmam.model.ProjectConfig;
import com.tmam.model.StartResult;
import com.tmam.model.TmamConfig;
import com.tmam.model.TomcatInstanceConfig;

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

	@Test
	void statusReturnsStoppedWhenPidFileExistsButProcessDead() throws IOException {
		Path pidsDir = tempDir.resolve("pids");
		Files.createDirectories(pidsDir);
		Files.writeString(pidsDir.resolve("dead-project.pid"), "999999999");

		assertEquals(InstanceStatus.STOPPED, processService.status("dead-project"));
	}

	@Test
	void startTomcatInstanceReturnsFailureWhenAlreadyRunning() throws Exception {
		Process tracked = mock(Process.class);
		when(tracked.isAlive()).thenReturn(true);
		@SuppressWarnings("unchecked")
		Map<String, Process> activeProcesses = (Map<String, Process>) ReflectionTestUtils
				.getField(processService, "activeProcesses");
		activeProcesses.put(TomcatInstanceConfig.DEFAULT_ID, tracked);

		TmamConfig config = new TmamConfig();
		TomcatInstanceConfig instance = new TomcatInstanceConfig();
		instance.setId(TomcatInstanceConfig.DEFAULT_ID);
		instance.setCatalinaHome("C:/Program Files/apache-tomcat-9.0.115");
		config.getTomcatInstances().put(TomcatInstanceConfig.DEFAULT_ID, instance);

		StartResult result = processService.startTomcatInstance(config, TomcatInstanceConfig.DEFAULT_ID);

		assertFalse(result.success());
		assertEquals("Tomcat 實例已在運行中", result.message());
	}

	@Test
	void isExternallyManagedFalseWhenManagedMarkerExists() throws IOException {
		Path pidsDir = tempDir.resolve("pids");
		Files.createDirectories(pidsDir);
		Files.writeString(pidsDir.resolve("default.managed"), "1");

		TmamConfig config = new TmamConfig();
		TomcatInstanceConfig instance = new TomcatInstanceConfig();
		instance.setId(TomcatInstanceConfig.DEFAULT_ID);
		instance.setGatewayPort(8080);
		config.getTomcatInstances().put(TomcatInstanceConfig.DEFAULT_ID, instance);

		assertFalse(processService.isExternallyManaged(config, TomcatInstanceConfig.DEFAULT_ID,
				InstanceStatus.RUNNING));
	}

}
