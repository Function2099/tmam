package com.tmam.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * TMAM 啟動完成後，若有啟用的路徑型服務就補開 Nginx，不必為此重啟 Tomcat。
 */
@Component
public class NginxAutoStart {

	private static final Logger log = LoggerFactory.getLogger(NginxAutoStart.class);

	private final TomcatInstanceManagementService instanceManagementService;
	private final boolean autoStart;

	public NginxAutoStart(
			TomcatInstanceManagementService instanceManagementService,
			@Value("${tmam.nginx.auto-start:true}") boolean autoStart) {
		this.instanceManagementService = instanceManagementService;
		this.autoStart = autoStart;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		if (!autoStart) {
			log.debug("[onApplicationReady] tmam.nginx.auto-start=false，略過");
			return;
		}
		log.info("[onApplicationReady] 檢查並啟動 Nginx（若有啟用的路徑型服務）");
		instanceManagementService.ensureNginxRunning();
	}

}
