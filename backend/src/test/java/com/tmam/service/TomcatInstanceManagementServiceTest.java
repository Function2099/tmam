package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import com.tmam.model.InstanceStatus;
import com.tmam.model.StartResult;
import com.tmam.model.TmamConfig;
import com.tmam.model.TomcatInstanceConfig;
import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;

@ExtendWith(MockitoExtension.class)
class TomcatInstanceManagementServiceTest {

	private static final String INSTANCE_ID = TomcatInstanceConfig.DEFAULT_ID;

	@TempDir
	Path tempDir;

	@Mock
	private ProcessService processService;

	@Mock
	private TomcatDiscoveryService tomcatDiscoveryService;

	private TomcatInstanceManagementService managementService;
	private ConfigService configService;
	private ServerXmlService serverXmlService;
	private Path catalinaHome;

	@BeforeEach
	void setUp() throws Exception {
		Path instancesRoot = tempDir.resolve("instances");
		catalinaHome = tempDir.resolve("tomcat");
		Files.createDirectories(catalinaHome.resolve("conf"));
		Files.copy(Path.of("src/test/resources/sample-server.xml"), catalinaHome.resolve("conf/server.xml"));

		XmlConfiguratorService xmlConfiguratorService = new XmlConfiguratorService(
				new ClassPathResource("server-template.xml"));
		NativeTomcatEnvironmentService nativeTomcatEnvironmentService = new NativeTomcatEnvironmentService(
				instancesRoot.toString(), xmlConfiguratorService);
		CatalinaHomeResolver catalinaHomeResolver = new CatalinaHomeResolver(
				new TomcatDiscoveryService(), catalinaHome.toString());
		serverXmlService = new ServerXmlService(
				catalinaHomeResolver,
				"PathGateway",
				nativeTomcatEnvironmentService);
		PathGatewayService pathGatewayService = new PathGatewayService(
				"PathGateway",
				"127.0.0.1",
				nativeTomcatEnvironmentService);
		ConfigMigrationService migrationService = new ConfigMigrationService(
				tempDir.resolve("projects.json").toString(),
				instancesRoot.toString(),
				tempDir.resolve("fragments").toString(),
				tempDir.resolve("native-base").toString(),
				catalinaHomeResolver);
		configService = new ConfigService(serverXmlService, migrationService);
		ReflectionTestUtils.setField(configService, "configPath",
				tempDir.resolve("projects.json").toString());
		ReflectionTestUtils.setField(configService, "mode", "native");

		NginxConfigService nginxConfigService = NginxConfigService.forTest(tempDir);
		managementService = new TomcatInstanceManagementService(
				configService,
				serverXmlService,
				processService,
				nativeTomcatEnvironmentService,
				pathGatewayService,
				nginxConfigService,
				tomcatDiscoveryService,
				new InstanceOperationLock());

		saveDefaultInstance();
	}

	@Test
	void applyAndStart_writesServerXmlAndStartsTomcat() throws Exception {
		when(processService.tomcatInstanceStatus(any(), eq(INSTANCE_ID))).thenReturn(InstanceStatus.STOPPED);
		when(processService.startTomcatInstance(any(), eq(INSTANCE_ID)))
				.thenReturn(StartResult.success(INSTANCE_ID));

		StartResult result = managementService.applyAndStart(INSTANCE_ID);

		assertTrue(result.success());
		verify(processService).startTomcatInstance(any(), eq(INSTANCE_ID));
		Path effectiveXml = serverXmlService.effectiveServerXmlPath(INSTANCE_ID);
		assertTrue(Files.exists(effectiveXml));
		assertTrue(Files.readString(effectiveXml).contains("<Service name=\"Portal_Area\">"));
	}

	@Test
	void applyAndStart_failsWhenNoServiceEnabledAndStopped() throws Exception {
		disableAllServices();
		when(processService.tomcatInstanceStatus(any(), eq(INSTANCE_ID))).thenReturn(InstanceStatus.STOPPED);

		StartResult result = managementService.applyAndStart(INSTANCE_ID);

		assertFalse(result.success());
		assertEquals("至少需要啟用一個 Service", result.message());
	}

	@Test
	void applyAndStart_stopsRunningTomcatBeforeApply() throws Exception {
		when(processService.tomcatInstanceStatus(any(), eq(INSTANCE_ID))).thenReturn(InstanceStatus.RUNNING);
		when(processService.startTomcatInstance(any(), eq(INSTANCE_ID)))
				.thenReturn(StartResult.success(INSTANCE_ID));

		StartResult result = managementService.applyAndStart(INSTANCE_ID);

		assertTrue(result.success());
		InOrder order = inOrder(processService);
		order.verify(processService).stopTomcatInstance(any(), eq(INSTANCE_ID));
		order.verify(processService).startTomcatInstance(any(), eq(INSTANCE_ID));
	}

	@Test
	void restart_stopsAppliesAndStarts() throws Exception {
		when(processService.startTomcatInstance(any(), eq(INSTANCE_ID)))
				.thenReturn(StartResult.success(INSTANCE_ID));

		StartResult result = managementService.restart(INSTANCE_ID);

		assertTrue(result.success());
		InOrder order = inOrder(processService);
		order.verify(processService).stopTomcatInstance(any(), eq(INSTANCE_ID));
		order.verify(processService).startTomcatInstance(any(), eq(INSTANCE_ID));
		assertTrue(Files.exists(serverXmlService.effectiveServerXmlPath(INSTANCE_ID)));
	}

	@Test
	void concurrentApplyAndStart_serializesPerInstance() throws Exception {
		CountDownLatch insideOperation = new CountDownLatch(1);
		CountDownLatch releaseOperation = new CountDownLatch(1);

		when(processService.tomcatInstanceStatus(any(), eq(INSTANCE_ID))).thenReturn(InstanceStatus.STOPPED);
		when(processService.startTomcatInstance(any(), eq(INSTANCE_ID))).thenAnswer(invocation -> {
			insideOperation.countDown();
			releaseOperation.await(5, TimeUnit.SECONDS);
			return StartResult.success(INSTANCE_ID);
		});

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<StartResult> first = executor.submit(() -> managementService.applyAndStart(INSTANCE_ID));
			assertTrue(insideOperation.await(5, TimeUnit.SECONDS), "first apply should reach startTomcatInstance");

			Future<StartResult> second = executor.submit(() -> managementService.applyAndStart(INSTANCE_ID));
			StartResult secondResult = second.get(2, TimeUnit.SECONDS);

			assertFalse(secondResult.success());
			assertEquals("操作進行中，請稍後再試", secondResult.message());

			releaseOperation.countDown();
			assertTrue(first.get(5, TimeUnit.SECONDS).success());
		}
		finally {
			executor.shutdownNow();
		}
	}

	private void saveDefaultInstance() throws Exception {
		TmamConfig config = new TmamConfig();
		config.setVersion(TmamConfig.VERSION_2);
		config.setMode("native");
		config.setCatalinaHome(catalinaHome.toString());

		TomcatInstanceConfig instance = new TomcatInstanceConfig();
		instance.setId(INSTANCE_ID);
		instance.setCatalinaHome(catalinaHome.toString());
		instance.setDisplayName("test-tomcat");
		instance.setGatewayPort(8080);
		instance.setShutdownPort(8005);
		instance.setServices(new LinkedHashMap<>());
		config.getTomcatInstances().put(INSTANCE_ID, instance);

		configService.save(config);
	}

	private void disableAllServices() throws Exception {
		TmamConfig config = configService.load();
		TomcatInstanceConfig instance = config.requireInstance(INSTANCE_ID);
		if (instance.getServices().isEmpty()) {
			List<TomcatServiceConfig> imported = serverXmlService.importFromServerXml(INSTANCE_ID,
					catalinaHome.toString());
			Map<String, TomcatServiceConfig> services = new LinkedHashMap<>();
			imported.forEach(service -> {
				service.setEnabled(false);
				services.put(service.getName(), service);
			});
			instance.setServices(services);
		}
		else {
			instance.getServices().values().forEach(service -> service.setEnabled(false));
		}
		configService.save(config);
	}

}
