package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
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
import org.springframework.test.util.ReflectionTestUtils;

import com.tmam.dto.TomcatServiceUpdateRequest;
import com.tmam.dto.TomcatServiceView;
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

		NativeTomcatEnvironmentService nativeTomcatEnvironmentService = new NativeTomcatEnvironmentService(
				instancesRoot.toString());
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
		Path effectiveXml = serverXmlService.effectiveServerXmlPath(catalinaHome.toString());
		assertTrue(Files.exists(effectiveXml));
		assertTrue(Files.readString(effectiveXml).contains("<Service name=\"Portal_Area\">"));
	}

	@Test
	void updateLegacyIpService_persistsAddressAndPort() throws Exception {
		when(processService.tomcatInstanceStatus(any(), eq(INSTANCE_ID))).thenReturn(InstanceStatus.STOPPED);

		TomcatServiceView updated = managementService.updateService(INSTANCE_ID, "Portal_Area",
				new TomcatServiceUpdateRequest(
						"南山國中",
						null,
						null,
						"192.168.10.20",
						8080,
						null,
						null,
						null,
						null,
						null));

		assertEquals("192.168.10.20", updated.address());
		assertEquals(8080, updated.port());
		assertEquals("南山國中", updated.displayName());

		TomcatServiceConfig saved = configService.load().requireInstance(INSTANCE_ID).getServices().get("Portal_Area");
		assertEquals("192.168.10.20", saved.getAddress());
		assertEquals(8080, saved.getPort());

		String fragment = Files.readString(serverXmlService.fragmentsDir(INSTANCE_ID).resolve("Portal_Area.xml"));
		assertTrue(fragment.contains("address=\"192.168.10.20\""));
		assertTrue(fragment.contains("port=\"8080\""));
		assertTrue(fragment.contains("redirectPort=\"443\""));
	}

	@Test
	void updateEnabled_allowsResavingWhenOnlyOneLegacyServiceStaysEnabled() throws Exception {
		managementService.ensureInstanceReady(INSTANCE_ID);
		Map<String, Boolean> onlySport = enabledSelection("Portal_Sport");

		managementService.updateEnabled(INSTANCE_ID, onlySport);
		managementService.updateEnabled(INSTANCE_ID, onlySport);

		TomcatInstanceConfig instance = configService.load().requireInstance(INSTANCE_ID);
		assertTrue(instance.getServices().get("Portal_Sport").isEnabled());
		assertEquals(1, instance.getServices().values().stream().filter(TomcatServiceConfig::isEnabled).count());
	}

	@Test
	void updateEnabled_canNarrowToOneWhenOtherLegacyServicesAreAlreadyDisabled() throws Exception {
		managementService.ensureInstanceReady(INSTANCE_ID);
		TmamConfig config = configService.load();
		TomcatInstanceConfig instance = config.requireInstance(INSTANCE_ID);
		instance.getServices().values().forEach(service -> service.setEnabled(
				"Portal_Area".equals(service.getName()) || "Portal_Sport".equals(service.getName())));
		configService.save(config);

		managementService.updateEnabled(INSTANCE_ID, enabledSelection("Portal_Sport"));

		TomcatInstanceConfig saved = configService.load().requireInstance(INSTANCE_ID);
		assertTrue(saved.getServices().get("Portal_Sport").isEnabled());
		assertEquals(1, saved.getServices().values().stream().filter(TomcatServiceConfig::isEnabled).count());
	}

	@Test
	void updateEnabled_rejectsWhenNoServiceRemainsEnabled() throws Exception {
		managementService.ensureInstanceReady(INSTANCE_ID);
		Map<String, Boolean> none = new LinkedHashMap<>();
		configService.load().requireInstance(INSTANCE_ID).getServices().keySet()
				.forEach(name -> none.put(name, false));

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> managementService.updateEnabled(INSTANCE_ID, none));

		assertEquals("至少需要啟用一個 Service", ex.getMessage());
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
		assertTrue(Files.exists(serverXmlService.effectiveServerXmlPath(catalinaHome.toString())));
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

	@Test
	void start_whenAlreadyRunning_ensuresNginxWithoutRestartingTomcat() throws Exception {
		addEnabledPathProxy();
		NginxConfigService nginx = mock(NginxConfigService.class);
		when(nginx.isEnabled()).thenReturn(true);
		when(nginx.isAvailable()).thenReturn(true);
		ReflectionTestUtils.setField(managementService, "nginxConfigService", nginx);
		when(processService.tomcatInstanceStatus(any(), eq(INSTANCE_ID))).thenReturn(InstanceStatus.RUNNING);

		StartResult result = managementService.start(INSTANCE_ID);

		assertTrue(result.success());
		assertEquals("Tomcat 已在運行中", result.message());
		verify(nginx).apply(any());
		verify(processService, never()).startTomcatInstance(any(), eq(INSTANCE_ID));
		verify(processService, never()).stopTomcatInstance(any(), eq(INSTANCE_ID));
	}

	@Test
	void ensureNginxRunning_swallowsApplyFailure() throws Exception {
		addEnabledPathProxy();
		NginxConfigService nginx = mock(NginxConfigService.class);
		when(nginx.isEnabled()).thenReturn(true);
		when(nginx.isAvailable()).thenReturn(true);
		doThrow(new IOException("bind :80")).when(nginx).apply(any());
		ReflectionTestUtils.setField(managementService, "nginxConfigService", nginx);

		managementService.ensureNginxRunning();

		verify(nginx).apply(any());
	}

	@Test
	void ensureNginxRunning_skipsWhenNoEnabledPathProxy() throws Exception {
		NginxConfigService nginx = mock(NginxConfigService.class);
		when(nginx.isEnabled()).thenReturn(true);
		when(nginx.isAvailable()).thenReturn(true);
		ReflectionTestUtils.setField(managementService, "nginxConfigService", nginx);

		managementService.ensureNginxRunning();

		verify(nginx, never()).apply(any());
	}

	private void addEnabledPathProxy() throws Exception {
		TmamConfig config = configService.load();
		TomcatInstanceConfig instance = config.requireInstance(INSTANCE_ID);
		TomcatServiceConfig service = new TomcatServiceConfig();
		service.setName("allowance");
		service.setDisplayName("獎補助");
		service.setType(TomcatServiceType.PATH_PROXY);
		service.setPathPrefix("/Allowance_Web");
		service.setEnabled(true);
		instance.getServices().put(service.getName(), service);
		configService.save(config);
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

	private Map<String, Boolean> enabledSelection(String keepName) throws Exception {
		Map<String, Boolean> selection = new LinkedHashMap<>();
		configService.load().requireInstance(INSTANCE_ID).getServices().keySet()
				.forEach(name -> selection.put(name, keepName.equals(name)));
		return selection;
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
