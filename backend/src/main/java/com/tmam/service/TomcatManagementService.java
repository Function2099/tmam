package com.tmam.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.tmam.dto.NginxStatusView;
import com.tmam.dto.TomcatServiceCreateRequest;
import com.tmam.dto.TomcatServiceUpdateRequest;
import com.tmam.dto.TomcatServiceView;
import com.tmam.dto.TomcatStatusView;
import com.tmam.model.StartResult;
import com.tmam.model.TmamConfig;
import com.tmam.model.TomcatInstanceConfig;
import com.tmam.model.TomcatServiceConfig;

/**
 * 向後相容層：委派至 {@link TomcatInstanceConfig#DEFAULT_ID} 實例。
 */
@Service
public class TomcatManagementService {

	private final TomcatInstanceManagementService instanceManagementService;

	public TomcatManagementService(TomcatInstanceManagementService instanceManagementService) {
		this.instanceManagementService = instanceManagementService;
	}

	public TmamConfig ensureReady() throws Exception {
		return instanceManagementService.ensureReady();
	}

	public List<TomcatServiceView> listServices() throws Exception {
		return instanceManagementService.listServices(TomcatInstanceConfig.DEFAULT_ID);
	}

	public TomcatStatusView tomcatStatus() throws Exception {
		return instanceManagementService.tomcatStatus(TomcatInstanceConfig.DEFAULT_ID);
	}

	public NginxStatusView nginxStatus() throws Exception {
		return instanceManagementService.nginxStatus();
	}

	public TomcatServiceView addPathProxyService(TomcatServiceCreateRequest request) throws Exception {
		return instanceManagementService.addService(TomcatInstanceConfig.DEFAULT_ID, request);
	}

	public TomcatServiceView updatePathProxyService(String name, TomcatServiceUpdateRequest request) throws Exception {
		return instanceManagementService.updateService(TomcatInstanceConfig.DEFAULT_ID, name, request);
	}

	public void deletePathProxyService(String name) throws Exception {
		instanceManagementService.deleteService(TomcatInstanceConfig.DEFAULT_ID, name);
	}

	public void applyNginx() throws Exception {
		instanceManagementService.applyNginx();
	}

	public void updateEnabled(Map<String, Boolean> enabledByName) throws Exception {
		instanceManagementService.updateEnabled(TomcatInstanceConfig.DEFAULT_ID, enabledByName);
	}

	public StartResult start() throws Exception {
		return instanceManagementService.start(TomcatInstanceConfig.DEFAULT_ID);
	}

	public void stop() throws Exception {
		instanceManagementService.stop(TomcatInstanceConfig.DEFAULT_ID);
	}

	public StartResult restart() throws Exception {
		return instanceManagementService.restart(TomcatInstanceConfig.DEFAULT_ID);
	}

	public StartResult applyAndStart() throws Exception {
		return instanceManagementService.applyAndStart(TomcatInstanceConfig.DEFAULT_ID);
	}

	public StartResult toggleService(String name) throws Exception {
		return instanceManagementService.toggleService(TomcatInstanceConfig.DEFAULT_ID, name);
	}

	public List<TomcatServiceConfig> importServices() throws Exception {
		return instanceManagementService.importServices(TomcatInstanceConfig.DEFAULT_ID);
	}

	public void restoreOriginal() throws Exception {
		instanceManagementService.restoreOriginal(TomcatInstanceConfig.DEFAULT_ID);
	}

	public List<String> logs(int lines) throws IOException {
		return instanceManagementService.logs(TomcatInstanceConfig.DEFAULT_ID, lines);
	}

}
