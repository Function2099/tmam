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
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
		NativeTomcatEnvironmentService nativeTomcatEnvironmentService = new NativeTomcatEnvironmentService(
				tempDir.resolve("instances").toString());
		CatalinaHomeResolver catalinaHomeResolver = new CatalinaHomeResolver(
				new TomcatDiscoveryService(), "C:/Program Files/apache-tomcat-9.0.115");
		ServerXmlService serverXmlService = new ServerXmlService(
				catalinaHomeResolver,
				"PathGateway",
				nativeTomcatEnvironmentService);
		ConfigMigrationService migrationService = new ConfigMigrationService(
				tempDir.resolve("projects.json").toString(),
				tempDir.resolve("instances").toString(),
				tempDir.resolve("fragments").toString(),
				tempDir.resolve("native-base").toString(),
				catalinaHomeResolver);
		configService = new ConfigService(serverXmlService, migrationService);
		ReflectionTestUtils.setField(configService, "configPath",
				tempDir.resolve("projects.json").toString());

		processService = new ProcessService(
				tempDir.resolve("instances").toString(),
				tempDir.resolve("pids").toString(),
				catalinaHomeResolver,
				30,
				configService,
				nativeTomcatEnvironmentService,
				new WindowsTomcatService());
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

	@Test
	void hasNewStartupMarkerIgnoresOldSuccessLine() throws IOException {
		Path logFile = tempDir.resolve("catalina.2026-08-18.log");
		Files.writeString(logFile, "18-Aug-2026 11:16:37.506 Server startup in [86198] milliseconds\n");
		long offset = Files.size(logFile);

		assertFalse(ProcessService.hasNewStartupMarker(logFile, offset));

		Files.writeString(logFile, Files.readString(logFile)
				+ "18-Aug-2026 11:36:38.810 Server startup in [107479] milliseconds\n");
		assertTrue(ProcessService.hasNewStartupMarker(logFile, offset));
	}

	@Test
	void monitorStartupUnlimitedWaitsForLateMarker() throws Exception {
		ReflectionTestUtils.setField(processService, "startupTimeoutSec", 0);
		Path catalinaBase = tempDir.resolve("slow-base");
		Path logsDir = catalinaBase.resolve("logs");
		Files.createDirectories(logsDir);
		Path logFile = logsDir.resolve("catalina.out");
		Files.writeString(logFile, "starting\n");
		long offset = Files.size(logFile);

		Thread writer = new Thread(() -> {
			try {
				Thread.sleep(1200);
				Files.writeString(logFile, Files.readString(logFile) + "Server startup in [999] milliseconds\n");
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		writer.start();
		StartResult result = (StartResult) ReflectionTestUtils.invokeMethod(
				processService, "monitorStartup", "slow", catalinaBase, offset, (BooleanSupplier) () -> false);
		writer.join();

		assertTrue(result.success());
	}

	@Test
	void monitorStartupStopsWaitingWhenProcessAborted() throws Exception {
		Path catalinaBase = tempDir.resolve("dead-base");
		Path logsDir = catalinaBase.resolve("logs");
		Files.createDirectories(logsDir);
		Files.writeString(logsDir.resolve("catalina.out"), "starting\n");

		StartResult result = (StartResult) ReflectionTestUtils.invokeMethod(
				processService, "monitorStartup", "dead", catalinaBase, 0L, (BooleanSupplier) () -> true);

		assertFalse(result.success());
		assertTrue(result.message().contains("啟動過程中行程已結束"));
		assertFalse(result.message().contains("記憶體不足"));
	}

	@Test
	void monitorStartupReportsNativeOutOfMemory() throws Exception {
		Path catalinaBase = tempDir.resolve("oom-base");
		Path logsDir = catalinaBase.resolve("logs");
		Files.createDirectories(logsDir);
		Files.writeString(logsDir.resolve("catalina.out"), "starting\n");
		Files.writeString(catalinaBase.resolve("hs_err_pid5624.log"), """
				#
				# There is insufficient memory for the Java Runtime Environment to continue.
				# Native memory allocation (malloc) failed to allocate 641008 bytes for Chunk::new
				#  Out of Memory Error (arena.cpp:189), pid=5624, tid=21508
				""");

		StartResult result = (StartResult) ReflectionTestUtils.invokeMethod(
				processService, "monitorStartup", "oom", catalinaBase, 0L, (BooleanSupplier) () -> true);

		assertFalse(result.success());
		assertTrue(result.message().contains("Java 原生記憶體不足"));
	}

	@Test
	void jvmCrashHintRecognizesFatalError() {
		assertTrue(ProcessService.jvmCrashHint("# A fatal error has been detected by the Java Runtime Environment:")
				.contains("異常終止"));
		assertEquals(null, ProcessService.jvmCrashHint("normal log"));
	}

}
