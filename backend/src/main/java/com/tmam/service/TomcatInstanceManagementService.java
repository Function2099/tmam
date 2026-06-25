package com.tmam.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.tmam.dto.NginxStatusView;
import com.tmam.dto.TomcatDiscoveryView;
import com.tmam.dto.TomcatInstanceCreateRequest;
import com.tmam.dto.TomcatInstanceUpdateRequest;
import com.tmam.dto.TomcatInstanceView;
import com.tmam.dto.TomcatServiceCreateRequest;
import com.tmam.dto.TomcatServiceUpdateRequest;
import com.tmam.dto.TomcatServiceView;
import com.tmam.dto.TomcatStatusView;
import com.tmam.model.InstanceStatus;
import com.tmam.model.StartResult;
import com.tmam.model.TmamConfig;
import com.tmam.model.TomcatInstanceConfig;
import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;
import com.tmam.util.CatalinaHomeNormalizer;

@Service
public class TomcatInstanceManagementService {

	private static final Logger log = LoggerFactory.getLogger(TomcatInstanceManagementService.class);

	private final ConfigService configService;
	private final ServerXmlService serverXmlService;
	private final ProcessService processService;
	private final NativeTomcatEnvironmentService nativeTomcatEnvironmentService;
	private final PathGatewayService pathGatewayService;
	private final NginxConfigService nginxConfigService;
	private final TomcatDiscoveryService tomcatDiscoveryService;
	private final InstanceOperationLock instanceOperationLock;

	public TomcatInstanceManagementService(ConfigService configService, ServerXmlService serverXmlService,
			ProcessService processService, NativeTomcatEnvironmentService nativeTomcatEnvironmentService,
			PathGatewayService pathGatewayService, NginxConfigService nginxConfigService,
			TomcatDiscoveryService tomcatDiscoveryService, InstanceOperationLock instanceOperationLock) {
		this.configService = configService;
		this.serverXmlService = serverXmlService;
		this.processService = processService;
		this.nativeTomcatEnvironmentService = nativeTomcatEnvironmentService;
		this.pathGatewayService = pathGatewayService;
		this.nginxConfigService = nginxConfigService;
		this.tomcatDiscoveryService = tomcatDiscoveryService;
		this.instanceOperationLock = instanceOperationLock;
	}

	public TmamConfig ensureReady() throws Exception {
		TmamConfig config = configService.load();
		if (!config.isNativeMode()) {
			throw new IllegalStateException("Tomcat management API requires native mode");
		}
		return config;
	}

	public TmamConfig ensureInstanceReady(String instanceId) throws Exception {
		TmamConfig config = ensureReady();
		config.requireInstance(instanceId);
		configService.ensureServicesImported(config, instanceId);
		config = configService.load();
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		nativeTomcatEnvironmentService.ensureInitialized(instanceId, instance.getCatalinaHome());
		return config;
	}

	public List<TomcatDiscoveryView> discover() throws Exception {
		TmamConfig config = ensureReady();
		var registeredHomes = config.getTomcatInstances().values().stream()
				.map(TomcatInstanceConfig::getCatalinaHome)
				.map(CatalinaHomeNormalizer::comparisonKey)
				.collect(Collectors.toSet());
		return tomcatDiscoveryService.discover().stream()
				.filter(view -> !registeredHomes.contains(CatalinaHomeNormalizer.comparisonKey(view.catalinaHome())))
				.toList();
	}

	public List<TomcatInstanceView> listInstances() throws Exception {
		TmamConfig config = ensureReady();
		return config.getTomcatInstances().entrySet().stream()
				.map(entry -> toInstanceView(entry.getKey(), entry.getValue(), config))
				.toList();
	}

	public Map<String, InstanceStatus> allInstanceStatus() throws Exception {
		TmamConfig config = ensureReady();
		Map<String, InstanceStatus> statusMap = new LinkedHashMap<>();
		for (String id : config.getTomcatInstances().keySet()) {
			statusMap.put(id, processService.tomcatInstanceStatus(config, id));
		}
		return statusMap;
	}

	public TomcatInstanceView getInstance(String instanceId) throws Exception {
		TmamConfig config = ensureReady();
		return toInstanceView(instanceId, config.requireInstance(instanceId), config);
	}

	public TomcatInstanceView createInstance(TomcatInstanceCreateRequest request) throws Exception {
		TmamConfig config = ensureReady();
		if (request.catalinaHome() == null || request.catalinaHome().isBlank()) {
			throw new IllegalArgumentException("catalinaHome 不可為空");
		}
		Path home = Path.of(request.catalinaHome().trim());
		if (!tomcatDiscoveryService.isTomcatHome(home)) {
			throw new IllegalArgumentException("無效的 Tomcat 目錄: " + request.catalinaHome());
		}

		for (Map.Entry<String, TomcatInstanceConfig> entry : config.getTomcatInstances().entrySet()) {
			if (CatalinaHomeNormalizer.isSame(entry.getValue().getCatalinaHome(), home.toString())) {
				throw new IllegalArgumentException(
						"此 Tomcat 安裝路徑已註冊為實例「" + entry.getKey() + "」，無需重複新增");
			}
		}

		String instanceId = request.id() != null && !request.id().isBlank()
				? request.id().trim()
				: config.getTomcatInstances().isEmpty()
						? TomcatInstanceConfig.DEFAULT_ID
						: configService.allocateInstanceId(config, home.toString());
		if (config.getTomcatInstances().containsKey(instanceId)) {
			throw new IllegalArgumentException("Tomcat 實例 ID 已存在: " + instanceId);
		}

		TomcatInstanceConfig instance = new TomcatInstanceConfig();
		instance.setId(instanceId);
		instance.setCatalinaHome(home.toString());
		instance.setDisplayName(request.displayName() != null && !request.displayName().isBlank()
				? request.displayName().trim()
				: home.getFileName().toString());
		instance.setGatewayPort(configService.allocateGatewayPort(config));
		instance.setShutdownPort(configService.allocateShutdownPort(config));
		instance.setServices(new LinkedHashMap<>());

		config.getTomcatInstances().put(instanceId, instance);
		configService.save(config);

		config = configService.load();
		importServices(instanceId);
		return getInstance(instanceId);
	}

	public TomcatInstanceView updateInstance(String instanceId, TomcatInstanceUpdateRequest request) throws Exception {
		TmamConfig config = ensureReady();
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		if (request.displayName() != null && !request.displayName().isBlank()) {
			instance.setDisplayName(request.displayName().trim());
		}
		if (request.jvmOpts() != null) {
			instance.setJvmOpts(request.jvmOpts().isBlank() ? null : request.jvmOpts().trim());
		}
		configService.save(config);
		return getInstance(instanceId);
	}

	public void deleteInstance(String instanceId) throws Exception {
		TmamConfig config = ensureReady();
		if (TomcatInstanceConfig.DEFAULT_ID.equals(instanceId)) {
			throw new IllegalArgumentException("無法刪除預設 Tomcat 實例");
		}
		if (processService.tomcatInstanceStatus(config, instanceId) == InstanceStatus.RUNNING) {
			throw new IllegalStateException("請先停止 Tomcat 實例再刪除");
		}
		nativeTomcatEnvironmentService.invalidate(instanceId);
		config.getTomcatInstances().remove(instanceId);
		configService.save(config);
	}

	public List<TomcatServiceView> listServices(String instanceId) throws Exception {
		TmamConfig config = ensureInstanceReady(instanceId);
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		InstanceStatus tomcatStatus = processService.tomcatInstanceStatus(config, instanceId);
		return instance.getServices().values().stream()
				.map(service -> toView(instanceId, instance, service, tomcatStatus))
				.toList();
	}

	public TomcatStatusView tomcatStatus(String instanceId) throws Exception {
		TmamConfig config = ensureInstanceReady(instanceId);
		InstanceStatus status = processService.tomcatInstanceStatus(config, instanceId);
		return new TomcatStatusView(status, processService.isExternallyManaged(config, instanceId, status));
	}

	public NginxStatusView nginxStatus() throws Exception {
		ensureReady();
		return new NginxStatusView(
				nginxConfigService.isEnabled(),
				nginxConfigService.isAvailable(),
				nginxConfigService.getExecutable(),
				nginxConfigService.getConfigPath().toString(),
				nginxConfigService.getLocationsFragment().toString(),
				nginxConfigService.getListenPort(),
				nginxConfigService.isAvailable() ? "Nginx 可用" : "Nginx 執行檔不存在或未啟用");
	}

	public TomcatServiceView addService(String instanceId, TomcatServiceCreateRequest request) throws Exception {
		TmamConfig config = ensureInstanceReady(instanceId);
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		TomcatServiceType type = request.type() != null ? request.type() : TomcatServiceType.PATH_PROXY;

		if (type == TomcatServiceType.PATH_PROXY) {
			return addPathProxyService(instanceId, config, instance, request);
		}
		return addLegacyIpService(instanceId, config, instance, request);
	}

	public TomcatServiceView updateService(String instanceId, String name, TomcatServiceUpdateRequest request)
			throws Exception {
		TmamConfig config = ensureInstanceReady(instanceId);
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		TomcatServiceConfig service = instance.getServices().get(name);
		PathProxyValidator.validateModifiable(service);

		if (request.displayName() != null && !request.displayName().isBlank()) {
			service.setDisplayName(request.displayName().trim());
		}
		if (service.isPathProxy()) {
			if (request.pathPrefix() != null && !request.pathPrefix().isBlank()) {
				PathProxyValidator.validatePathPrefixAcrossInstances(request.pathPrefix(), config, instanceId, name);
				service.setPathPrefix(PathProxyValidator.normalizePathPrefix(request.pathPrefix()));
			}
			if (request.docBase() != null && !request.docBase().isBlank()) {
				PathProxyValidator.validateDocBase(request.docBase());
				service.setDocBase(request.docBase().trim());
			}
			if (request.proxyStripPrefix() != null) {
				service.setProxyStripPrefix(request.proxyStripPrefix());
			}
		}
		else {
			boolean addressChanged = false;
			if (request.address() != null && !request.address().isBlank()) {
				service.setAddress(request.address().trim());
				addressChanged = true;
			}
			if (request.port() != null) {
				service.setPort(request.port());
				addressChanged = true;
			}
			if (request.docBase() != null && !request.docBase().isBlank()) {
				PathProxyValidator.validateDocBase(request.docBase());
				service.setDocBase(request.docBase().trim());
			}
			if (addressChanged || request.docBase() != null) {
				serverXmlService.writeServiceFragment(instanceId, name,
						LegacyIpFragmentBuilder.build(service));
			}
		}
		if (request.enabled() != null) {
			service.setEnabled(request.enabled());
		}

		refreshPathProxyArtifacts(config, instanceId);
		configService.save(config);
		return toView(instanceId, instance, service, processService.tomcatInstanceStatus(config, instanceId));
	}

	public void deleteService(String instanceId, String name) throws Exception {
		TmamConfig config = ensureInstanceReady(instanceId);
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		TomcatServiceConfig service = instance.getServices().get(name);
		PathProxyValidator.validateDeletable(instance, name, service);
		instance.getServices().remove(name);
		serverXmlService.deleteServiceFragment(instanceId, name);
		refreshPathProxyArtifacts(config, instanceId);
		configService.save(config);
	}

	public void applyNginx() throws Exception {
		TmamConfig config = ensureReady();
		nginxConfigService.apply(config);
	}

	public void updateEnabled(String instanceId, Map<String, Boolean> enabledByName) throws Exception {
		TmamConfig config = ensureInstanceReady(instanceId);
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		List<String> enabledNow = new ArrayList<>();
		List<String> disabledNow = new ArrayList<>();
		enabledByName.forEach((name, enabled) -> {
			TomcatServiceConfig service = instance.getServices().get(name);
			if (service == null) {
				throw new IllegalArgumentException("未知 Service: " + name);
			}
			if (service.isLegacyIp() && !enabled && countEnabledLegacy(instance) <= 1
					&& hasLegacyIpServices(instance)) {
				throw new IllegalArgumentException("至少需要保留一個啟用的 IP 型 Service");
			}
			boolean wasEnabled = service.isEnabled();
			if (wasEnabled != enabled) {
				String label = serviceLabel(service);
				if (enabled) {
					enabledNow.add(label);
				}
				else {
					disabledNow.add(label);
				}
			}
			service.setEnabled(enabled);
		});
		ensureAtLeastOneEnabled(instance);
		logEnabledSelectionChanges(instanceId, enabledNow, disabledNow);
		refreshPathProxyArtifacts(config, instanceId);
		configService.save(config);
	}

	public StartResult start(String instanceId) throws Exception {
		try {
			return instanceOperationLock.withLock(instanceId, () -> doStart(instanceId));
		}
		catch (InstanceOperationLock.InstanceOperationBusyException e) {
			return instanceOperationLock.busyResult(instanceId);
		}
	}

	public void stop(String instanceId) throws Exception {
		try {
			instanceOperationLock.withLock(instanceId, () -> {
				doStop(instanceId);
				return null;
			});
		}
		catch (InstanceOperationLock.InstanceOperationBusyException e) {
			throw new IllegalStateException("操作進行中，請稍後再試");
		}
	}

	public StartResult restart(String instanceId) throws Exception {
		try {
			return instanceOperationLock.withLock(instanceId, () -> doRestart(instanceId));
		}
		catch (InstanceOperationLock.InstanceOperationBusyException e) {
			return instanceOperationLock.busyResult(instanceId);
		}
	}

	public StartResult applyAndStart(String instanceId) throws Exception {
		try {
			return instanceOperationLock.withLock(instanceId, () -> doApplyAndStart(instanceId));
		}
		catch (InstanceOperationLock.InstanceOperationBusyException e) {
			return instanceOperationLock.busyResult(instanceId);
		}
	}

	public StartResult toggleService(String instanceId, String name) throws Exception {
		try {
			return instanceOperationLock.withLock(instanceId, () -> doToggleService(instanceId, name));
		}
		catch (InstanceOperationLock.InstanceOperationBusyException e) {
			return instanceOperationLock.busyResult(instanceId);
		}
	}

	private StartResult doStart(String instanceId) throws Exception {
		TmamConfig config = ensureInstanceReady(instanceId);
		if (processService.tomcatInstanceStatus(config, instanceId) == InstanceStatus.RUNNING) {
			return StartResult.failure(instanceId, "Tomcat 已在運行中");
		}
		applyArtifacts(config, instanceId);
		return processService.startTomcatInstance(config, instanceId);
	}

	private void doStop(String instanceId) throws Exception {
		TmamConfig config = ensureInstanceReady(instanceId);
		processService.stopTomcatInstance(config, instanceId);
	}

	private StartResult doRestart(String instanceId) throws Exception {
		TmamConfig config = ensureInstanceReady(instanceId);
		processService.stopTomcatInstance(config, instanceId);
		Thread.sleep(1000);
		applyArtifacts(config, instanceId);
		return processService.startTomcatInstance(config, instanceId);
	}

	private StartResult doApplyAndStart(String instanceId) throws Exception {
		TmamConfig config = ensureInstanceReady(instanceId);
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		List<String> enabledServices = instance.getServices().values().stream()
				.filter(TomcatServiceConfig::isEnabled)
				.map(this::serviceLabel)
				.toList();
		log.info("[applyAndStart] instance={} 開始套用，已勾選 {}/{} 個 Service: {}",
				instanceId, enabledServices.size(), instance.getServices().size(), enabledServices);

		boolean wasRunning = processService.tomcatInstanceStatus(config, instanceId) == InstanceStatus.RUNNING;
		if (!wasRunning && instance.getServices().values().stream().noneMatch(TomcatServiceConfig::isEnabled)) {
			log.warn("[applyAndStart] instance={} 套用失敗：未勾選任何 Service", instanceId);
			return StartResult.failure(instanceId, "至少需要啟用一個 Service");
		}

		if (wasRunning) {
			log.info("[applyAndStart] instance={} 停止運行中的 Tomcat 以套用新設定", instanceId);
			processService.stopTomcatInstance(config, instanceId);
			Thread.sleep(1000);
		}

		log.info("[applyAndStart] instance={} 寫入 server.xml、PathGateway 片段與 Nginx 設定", instanceId);
		applyArtifacts(config, instanceId);

		log.info("[applyAndStart] instance={} 啟動 Tomcat", instanceId);
		StartResult result = processService.startTomcatInstance(config, instanceId);
		if (result.success()) {
			log.info("[applyAndStart] instance={} 套用完成，Tomcat 已啟動", instanceId);
		}
		else {
			log.warn("[applyAndStart] instance={} 套用完成但 Tomcat 啟動失敗: {}", instanceId, result.message());
		}
		return result;
	}

	private StartResult doToggleService(String instanceId, String name) throws Exception {
		TmamConfig config = ensureInstanceReady(instanceId);
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		TomcatServiceConfig service = instance.getServices().get(name);
		if (service == null) {
			throw new IllegalArgumentException("未知 Service: " + name);
		}

		boolean wasRunning = processService.tomcatInstanceStatus(config, instanceId) == InstanceStatus.RUNNING;
		if (service.isLegacyIp() && service.isEnabled() && countEnabledLegacy(instance) <= 1
				&& hasLegacyIpServices(instance)) {
			throw new IllegalArgumentException("至少需要保留一個啟用的 IP 型 Service");
		}

		if (wasRunning) {
			processService.stopTomcatInstance(config, instanceId);
			Thread.sleep(1000);
		}

		service.setEnabled(!service.isEnabled());
		ensureAtLeastOneEnabled(instance);
		refreshPathProxyArtifacts(config, instanceId);
		configService.save(config);
		applyServerXml(config, instanceId);
		applyNginxIfAvailable(config);

		if (wasRunning) {
			return processService.startTomcatInstance(config, instanceId);
		}
		return StartResult.success(instanceId);
	}

	public List<TomcatServiceConfig> importServices(String instanceId) throws Exception {
		TmamConfig config = ensureInstanceReady(instanceId);
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		List<TomcatServiceConfig> imported = serverXmlService.importFromServerXml(instanceId,
				instance.getCatalinaHome());
		instance.setServices(serverXmlService.mergeImportedServices(imported, instance.getServices()));
		instance.setShutdownPort(serverXmlService.readShutdownPortFromHeader(instanceId));
		refreshPathProxyArtifacts(config, instanceId);
		configService.save(config);
		return imported;
	}

	public void restoreOriginal(String instanceId) throws Exception {
		TmamConfig config = ensureInstanceReady(instanceId);
		if (processService.tomcatInstanceStatus(config, instanceId) == InstanceStatus.RUNNING) {
			throw new IllegalStateException("請先停止 Tomcat 再還原 server.xml");
		}
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		serverXmlService.restoreOriginal(instanceId, instance.getCatalinaHome());
	}

	public List<String> logs(String instanceId, int lines) throws IOException {
		return processService.getTomcatInstanceLogs(instanceId, lines);
	}

	private TomcatServiceView addPathProxyService(String instanceId, TmamConfig config,
			TomcatInstanceConfig instance, TomcatServiceCreateRequest request) throws Exception {
		validatePathProxyCreate(request, config, instanceId);
		String normalizedPrefix = PathProxyValidator.normalizePathPrefix(request.pathPrefix());
		TomcatServiceConfig service = new TomcatServiceConfig();
		service.setName(request.name().trim());
		service.setDisplayName(request.displayName() != null && !request.displayName().isBlank()
				? request.displayName().trim()
				: request.name().trim());
		service.setType(TomcatServiceType.PATH_PROXY);
		service.setPathPrefix(normalizedPrefix);
		service.setDocBase(request.docBase().trim());
		service.setEnabled(request.enabled() == null || request.enabled());
		service.setProxyStripPrefix(Boolean.TRUE.equals(request.proxyStripPrefix()));
		service.setUserCreated(true);
		service.setAddress("127.0.0.1");
		service.setPort(nginxConfigService.getListenPort());

		instance.getServices().put(service.getName(), service);
		refreshPathProxyArtifacts(config, instanceId);
		configService.save(config);
		return toView(instanceId, instance, service, processService.tomcatInstanceStatus(config, instanceId));
	}

	private TomcatServiceView addLegacyIpService(String instanceId, TmamConfig config,
			TomcatInstanceConfig instance, TomcatServiceCreateRequest request) throws Exception {
		validateLegacyIpCreate(request, config, instanceId);
		TomcatServiceConfig service = new TomcatServiceConfig();
		service.setName(request.name().trim());
		service.setDisplayName(request.displayName() != null && !request.displayName().isBlank()
				? request.displayName().trim()
				: request.name().trim());
		service.setType(TomcatServiceType.LEGACY_IP);
		service.setAddress(request.address().trim());
		service.setPort(request.port());
		service.setDocBase(request.docBase().trim());
		service.setEnabled(request.enabled() == null || request.enabled());
		service.setUserCreated(true);

		String fragment = LegacyIpFragmentBuilder.build(service);
		serverXmlService.writeServiceFragment(instanceId, service.getName(), fragment);
		instance.getServices().put(service.getName(), service);
		configService.save(config);
		return toView(instanceId, instance, service, processService.tomcatInstanceStatus(config, instanceId));
	}

	private void validatePathProxyCreate(TomcatServiceCreateRequest request, TmamConfig config, String instanceId) {
		if (request.name() == null || request.name().isBlank()) {
			throw new IllegalArgumentException("系統名稱不可為空");
		}
		PathProxyValidator.validateNotReservedName(request.name().trim(), pathGatewayService.getServiceName());
		PathProxyValidator.validateNameAcrossInstances(request.name().trim(), config, instanceId, null);
		PathProxyValidator.validatePathPrefixAcrossInstances(request.pathPrefix(), config, instanceId, null);
		PathProxyValidator.validateDocBase(request.docBase());
	}

	private void validateLegacyIpCreate(TomcatServiceCreateRequest request, TmamConfig config, String instanceId) {
		if (request.name() == null || request.name().isBlank()) {
			throw new IllegalArgumentException("系統名稱不可為空");
		}
		PathProxyValidator.validateNotReservedName(request.name().trim(), pathGatewayService.getServiceName());
		PathProxyValidator.validateNameAcrossInstances(request.name().trim(), config, instanceId, null);
		PathProxyValidator.validateLegacyIp(request.address(), request.port());
		PathProxyValidator.validateDocBase(request.docBase());
	}

	private void refreshPathProxyArtifacts(TmamConfig config, String instanceId) throws IOException {
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		pathGatewayService.writeFragment(instanceId, instance.getGatewayPort(), instance.getServices().values());
		nginxConfigService.writeConfig(config);
	}

	private void applyArtifacts(TmamConfig config, String instanceId) throws Exception {
		refreshPathProxyArtifacts(config, instanceId);
		applyServerXml(config, instanceId);
		applyNginxIfAvailable(config);
	}

	private void applyNginxIfAvailable(TmamConfig config) throws IOException, InterruptedException {
		if (!nginxConfigService.isEnabled() || !nginxConfigService.isAvailable()) {
			return;
		}
		boolean hasEnabledPathProxy = config.getTomcatInstances().values().stream()
				.flatMap(i -> i.getServices().values().stream())
				.anyMatch(service -> service.isPathProxy() && service.isEnabled());
		if (!hasEnabledPathProxy) {
			return;
		}
		nginxConfigService.testConfig();
		nginxConfigService.reloadOrStart();
	}

	private void applyServerXml(TmamConfig config, String instanceId) throws IOException {
		TomcatInstanceConfig instance = config.requireInstance(instanceId);
		serverXmlService.writeEffectiveServerXml(instanceId, instance.getCatalinaHome(), instance.getServices());
	}

	private void logEnabledSelectionChanges(String instanceId, List<String> enabledNow, List<String> disabledNow) {
		if (!enabledNow.isEmpty()) {
			log.info("[updateEnabled] instance={} 勾選啟用: {}", instanceId, enabledNow);
		}
		if (!disabledNow.isEmpty()) {
			log.info("[updateEnabled] instance={} 取消勾選: {}", instanceId, disabledNow);
		}
		if (enabledNow.isEmpty() && disabledNow.isEmpty()) {
			log.info("[updateEnabled] instance={} 勾選狀態與先前相同，已儲存", instanceId);
		}
	}

	private String serviceLabel(TomcatServiceConfig service) {
		String displayName = service.getDisplayName();
		if (displayName != null && !displayName.isBlank()) {
			return displayName + " (" + service.getName() + ")";
		}
		return service.getName();
	}

	private void ensureAtLeastOneEnabled(TomcatInstanceConfig instance) {
		if (instance.getServices().values().stream().noneMatch(TomcatServiceConfig::isEnabled)) {
			throw new IllegalArgumentException("至少需要啟用一個 Service");
		}
	}

	private long countEnabledLegacy(TomcatInstanceConfig instance) {
		return instance.getServices().values().stream()
				.filter(TomcatServiceConfig::isLegacyIp)
				.filter(TomcatServiceConfig::isEnabled)
				.count();
	}

	private boolean hasLegacyIpServices(TomcatInstanceConfig instance) {
		return instance.getServices().values().stream().anyMatch(TomcatServiceConfig::isLegacyIp);
	}

	private TomcatInstanceView toInstanceView(String instanceId, TomcatInstanceConfig instance, TmamConfig config) {
		long enabled = instance.getServices().values().stream().filter(TomcatServiceConfig::isEnabled).count();
		InstanceStatus status = processService.tomcatInstanceStatus(config, instanceId);
		return new TomcatInstanceView(
				instanceId,
				instance.getDisplayName(),
				instance.getCatalinaHome(),
				instance.getShutdownPort(),
				instance.getGatewayPort(),
				(int) enabled,
				instance.getServices().size(),
				status.name());
	}

	private TomcatServiceView toView(String instanceId, TomcatInstanceConfig instance, TomcatServiceConfig service,
			InstanceStatus tomcatStatus) {
		InstanceStatus serviceStatus;
		if (tomcatStatus != InstanceStatus.RUNNING) {
			serviceStatus = InstanceStatus.STOPPED;
		}
		else if (!service.isEnabled()) {
			serviceStatus = InstanceStatus.STOPPED;
		}
		else if (service.isPathProxy()) {
			serviceStatus = processService.isPathProxyHealthy(
					pathGatewayService.getUpstreamHost(),
					instance.getGatewayPort(),
					service.getPathPrefix())
							? InstanceStatus.RUNNING
							: InstanceStatus.ERROR;
		}
		else {
			serviceStatus = processService.isServicePortListening(service.getAddress(), service.getPort())
					? InstanceStatus.RUNNING
					: InstanceStatus.STOPPED;
		}

		String publicUrl = service.isPathProxy()
				? "http://localhost:" + nginxConfigService.getListenPort()
						+ PathProxyValidator.normalizePathPrefix(service.getPathPrefix()) + "/"
				: null;

		return new TomcatServiceView(
				service.getName(),
				service.getDisplayName() != null ? service.getDisplayName() : service.getName(),
				service.getType(),
				service.getAddress(),
				service.getPort(),
				service.isEnabled(),
				serviceStatus,
				service.getPathPrefix(),
				service.getDocBase(),
				publicUrl,
				service.isProxyStripPrefix(),
				service.isUserCreated());
	}

}
