package com.tmam.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.tmam.dto.NginxStatusView;
import com.tmam.dto.TomcatServiceCreateRequest;
import com.tmam.dto.TomcatServiceUpdateRequest;
import com.tmam.dto.TomcatServiceView;
import com.tmam.dto.TomcatStatusView;
import com.tmam.model.InstanceStatus;
import com.tmam.model.StartResult;
import com.tmam.model.TmamConfig;
import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;

@Service
public class TomcatManagementService {

	private static final Logger log = LoggerFactory.getLogger(TomcatManagementService.class);

	private final ConfigService configService;
	private final ServerXmlService serverXmlService;
	private final ProcessService processService;
	private final NativeTomcatEnvironmentService nativeTomcatEnvironmentService;
	private final PathGatewayService pathGatewayService;
	private final NginxConfigService nginxConfigService;

	public TomcatManagementService(ConfigService configService, ServerXmlService serverXmlService,
			ProcessService processService, NativeTomcatEnvironmentService nativeTomcatEnvironmentService,
			PathGatewayService pathGatewayService, NginxConfigService nginxConfigService) {
		this.configService = configService;
		this.serverXmlService = serverXmlService;
		this.processService = processService;
		this.nativeTomcatEnvironmentService = nativeTomcatEnvironmentService;
		this.pathGatewayService = pathGatewayService;
		this.nginxConfigService = nginxConfigService;
	}

	public TmamConfig ensureReady() throws Exception {
		TmamConfig config = configService.load();
		if (!config.isNativeMode()) {
			throw new IllegalStateException("Tomcat management API requires native mode");
		}
		configService.ensureServicesImported(config);
		config = configService.load();
		nativeTomcatEnvironmentService.ensureInitialized(config.getCatalinaHome());
		return config;
	}

	public List<TomcatServiceView> listServices() throws Exception {
		TmamConfig config = ensureReady();
		InstanceStatus tomcatStatus = processService.nativeTomcatStatus(config);
		return config.getServices().values().stream()
				.map(service -> toView(service, tomcatStatus))
				.toList();
	}

	public TomcatStatusView tomcatStatus() throws Exception {
		TmamConfig config = ensureReady();
		InstanceStatus status = processService.nativeTomcatStatus(config);
		return new TomcatStatusView(status, processService.isExternallyManaged(config, status));
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

	public TomcatServiceView addPathProxyService(TomcatServiceCreateRequest request) throws Exception {
		TmamConfig config = ensureReady();
		validateCreateRequest(request, config);

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
		service.setAddress("127.0.0.1");
		service.setPort(nginxConfigService.getListenPort());

		config.getServices().put(service.getName(), service);
		refreshPathProxyArtifacts(config);
		configService.save(config);
		log.info("[addPathProxyService] 已新增 PATH_PROXY: {}", service.getName());
		return toView(service, processService.nativeTomcatStatus(config));
	}

	public TomcatServiceView updatePathProxyService(String name, TomcatServiceUpdateRequest request) throws Exception {
		TmamConfig config = ensureReady();
		TomcatServiceConfig service = config.getServices().get(name);
		PathProxyValidator.validateNotLegacy(service);

		if (request.displayName() != null && !request.displayName().isBlank()) {
			service.setDisplayName(request.displayName().trim());
		}
		if (request.pathPrefix() != null && !request.pathPrefix().isBlank()) {
			PathProxyValidator.validatePathPrefix(request.pathPrefix(), config.getServices().values(), name);
			service.setPathPrefix(PathProxyValidator.normalizePathPrefix(request.pathPrefix()));
		}
		if (request.docBase() != null && !request.docBase().isBlank()) {
			PathProxyValidator.validateDocBase(request.docBase());
			service.setDocBase(request.docBase().trim());
		}
		if (request.enabled() != null) {
			service.setEnabled(request.enabled());
		}
		if (request.proxyStripPrefix() != null) {
			service.setProxyStripPrefix(request.proxyStripPrefix());
		}

		refreshPathProxyArtifacts(config);
		configService.save(config);
		log.info("[updatePathProxyService] 已更新 PATH_PROXY: {}", name);
		return toView(service, processService.nativeTomcatStatus(config));
	}

	public void deletePathProxyService(String name) throws Exception {
		TmamConfig config = ensureReady();
		TomcatServiceConfig service = config.getServices().get(name);
		PathProxyValidator.validateNotLegacy(service);
		config.getServices().remove(name);
		refreshPathProxyArtifacts(config);
		configService.save(config);
		log.info("[deletePathProxyService] 已刪除 PATH_PROXY: {}", name);
	}

	public void applyNginx() throws Exception {
		TmamConfig config = ensureReady();
		nginxConfigService.apply(config);
	}

	public void updateEnabled(Map<String, Boolean> enabledByName) throws Exception {
		log.info("[updateEnabled] 開始更新 Service 啟用狀態: {}", enabledByName);
		TmamConfig config = ensureReady();
		enabledByName.forEach((name, enabled) -> {
			TomcatServiceConfig service = config.getServices().get(name);
			if (service == null) {
				throw new IllegalArgumentException("未知 Service: " + name);
			}
			if (service.isLegacyIp() && !enabled && countEnabledLegacy(config) <= 1) {
				throw new IllegalArgumentException("至少需要保留一個啟用的 IP 型 Service");
			}
			log.debug("[updateEnabled] {} -> enabled={}", name, enabled);
			service.setEnabled(enabled);
		});
		ensureAtLeastOneEnabled(config);
		refreshPathProxyArtifacts(config);
		configService.save(config);
		log.info("[updateEnabled] 設定已儲存");
	}

	public StartResult start() throws Exception {
		TmamConfig config = ensureReady();
		if (processService.nativeTomcatStatus(config) == InstanceStatus.RUNNING) {
			return StartResult.failure("tomcat", "Tomcat 已在運行中");
		}
		applyArtifacts(config);
		return processService.startNativeTomcat(config);
	}

	public void stop() throws Exception {
		TmamConfig config = ensureReady();
		processService.stopNativeTomcat(config);
	}

	public StartResult restart() throws Exception {
		TmamConfig config = ensureReady();
		processService.stopNativeTomcat(config);
		Thread.sleep(1000);
		applyArtifacts(config);
		return processService.startNativeTomcat(config);
	}

	public StartResult applyAndStart() throws Exception {
		log.info("[applyAndStart] ===== 開始套用並啟動 =====");
		TmamConfig config = ensureReady();
		log.info("[applyAndStart] catalinaHome={}, services={}", config.getCatalinaHome(),
				config.getServices().entrySet().stream()
						.collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().isEnabled())));

		boolean wasRunning = processService.nativeTomcatStatus(config) == InstanceStatus.RUNNING;
		log.info("[applyAndStart] 步驟 1/5：檢查 Tomcat 狀態 -> wasRunning={}", wasRunning);

		if (wasRunning) {
			log.info("[applyAndStart] 步驟 2/5：停止 Tomcat...");
			processService.stopNativeTomcat(config);
			log.info("[applyAndStart] 步驟 2/5：Tomcat 已停止，等待 1 秒");
			Thread.sleep(1000);
		}
		else {
			log.info("[applyAndStart] 步驟 2/5：跳過停止（Tomcat 未在運行）");
		}

		log.info("[applyAndStart] 步驟 3/5：寫入 server.xml 與 Nginx...");
		applyArtifacts(config);
		log.info("[applyAndStart] 步驟 3/5：設定已更新");

		if (!wasRunning && config.getServices().values().stream().noneMatch(TomcatServiceConfig::isEnabled)) {
			log.warn("[applyAndStart] 失敗：Tomcat 未運行且沒有啟用的 Service");
			return StartResult.failure("tomcat", "至少需要啟用一個 Service");
		}

		log.info("[applyAndStart] 步驟 4/5：啟動 Tomcat...");
		StartResult result = processService.startNativeTomcat(config);
		if (result.success()) {
			log.info("[applyAndStart] ===== 完成：啟動成功 =====");
		}
		else {
			log.error("[applyAndStart] ===== 完成：啟動失敗 - {} =====", result.message());
		}
		return result;
	}

	public StartResult toggleService(String name) throws Exception {
		TmamConfig config = ensureReady();
		TomcatServiceConfig service = config.getServices().get(name);
		if (service == null) {
			throw new IllegalArgumentException("未知 Service: " + name);
		}

		boolean wasRunning = processService.nativeTomcatStatus(config) == InstanceStatus.RUNNING;
		if (service.isLegacyIp() && service.isEnabled() && countEnabledLegacy(config) <= 1) {
			throw new IllegalArgumentException("至少需要保留一個啟用的 IP 型 Service");
		}

		if (wasRunning) {
			processService.stopNativeTomcat(config);
			Thread.sleep(1000);
		}

		service.setEnabled(!service.isEnabled());
		ensureAtLeastOneEnabled(config);
		refreshPathProxyArtifacts(config);
		configService.save(config);
		applyServerXml(config);
		applyNginxIfAvailable(config);

		if (wasRunning) {
			return processService.startNativeTomcat(config);
		}
		return StartResult.success("tomcat");
	}

	public List<TomcatServiceConfig> importServices() throws Exception {
		TmamConfig config = ensureReady();
		List<TomcatServiceConfig> imported = serverXmlService.importFromServerXml(config.getCatalinaHome());
		config.setServices(serverXmlService.mergeImportedServices(imported, config.getServices()));
		refreshPathProxyArtifacts(config);
		configService.save(config);
		return imported;
	}

	public void restoreOriginal() throws Exception {
		TmamConfig config = ensureReady();
		if (processService.nativeTomcatStatus(config) == InstanceStatus.RUNNING) {
			throw new IllegalStateException("請先停止 Tomcat 再還原 server.xml");
		}
		serverXmlService.restoreOriginal(config.getCatalinaHome());
	}

	public List<String> logs(int lines) throws IOException {
		return processService.getNativeTomcatLogs(lines);
	}

	private void validateCreateRequest(TomcatServiceCreateRequest request, TmamConfig config) {
		if (request.name() == null || request.name().isBlank()) {
			throw new IllegalArgumentException("系統名稱不可為空");
		}
		PathProxyValidator.validateNotReservedName(request.name().trim(), pathGatewayService.getServiceName());
		PathProxyValidator.validateName(request.name(), config.getServices().values(), null);
		PathProxyValidator.validatePathPrefix(request.pathPrefix(), config.getServices().values(), null);
		PathProxyValidator.validateDocBase(request.docBase());
	}

	private void refreshPathProxyArtifacts(TmamConfig config) throws IOException {
		pathGatewayService.writeFragment(config.getServices().values());
		nginxConfigService.writeConfig(config);
	}

	private void applyArtifacts(TmamConfig config) throws Exception {
		refreshPathProxyArtifacts(config);
		applyServerXml(config);
		applyNginxIfAvailable(config);
	}

	private void applyNginxIfAvailable(TmamConfig config) throws IOException, InterruptedException {
		if (!nginxConfigService.isEnabled() || !nginxConfigService.isAvailable()) {
			log.info("[applyNginxIfAvailable] 略過 Nginx reload");
			return;
		}
		boolean hasEnabledPathProxy = config.getServices().values().stream()
				.anyMatch(service -> service.isPathProxy() && service.isEnabled());
		if (!hasEnabledPathProxy) {
			log.info("[applyNginxIfAvailable] 無啟用的 PATH_PROXY，略過 Nginx reload");
			return;
		}
		nginxConfigService.testConfig();
		nginxConfigService.reloadOrStart();
	}

	private void applyServerXml(TmamConfig config) throws IOException {
		List<String> enabledNames = config.getServices().entrySet().stream()
				.filter(entry -> entry.getValue().isEnabled())
				.map(Map.Entry::getKey)
				.toList();
		log.info("[applyServerXml] 寫入 server.xml，啟用 Service: {}", enabledNames);
		serverXmlService.writeEffectiveServerXml(config.getCatalinaHome(), config.getServices());
		log.info("[applyServerXml] server.xml 路徑: {}",
				serverXmlService.effectiveServerXmlPath(config.getCatalinaHome()));
	}

	private void ensureAtLeastOneEnabled(TmamConfig config) {
		if (config.getServices().values().stream().noneMatch(TomcatServiceConfig::isEnabled)) {
			throw new IllegalArgumentException("至少需要啟用一個 Service");
		}
	}

	private long countEnabledLegacy(TmamConfig config) {
		return config.getServices().values().stream()
				.filter(TomcatServiceConfig::isLegacyIp)
				.filter(TomcatServiceConfig::isEnabled)
				.count();
	}

	private TomcatServiceView toView(TomcatServiceConfig service, InstanceStatus tomcatStatus) {
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
					pathGatewayService.getGatewayPort(),
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
				service.isProxyStripPrefix());
	}

}
