package com.tmam.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NativeTomcatEnvironmentService {

	private static final Logger log = LoggerFactory.getLogger(NativeTomcatEnvironmentService.class);

	private static final List<String> BASE_DIRS = List.of("conf", "logs", "temp", "work", "webapps");

	private final String nativeCatalinaBase;
	private final XmlConfiguratorService xmlConfiguratorService;

	public NativeTomcatEnvironmentService(@Value("${tmam.native-catalina-base}") String nativeCatalinaBase,
			XmlConfiguratorService xmlConfiguratorService) {
		this.nativeCatalinaBase = nativeCatalinaBase;
		this.xmlConfiguratorService = xmlConfiguratorService;
	}

	public Path getNativeCatalinaBase() {
		return Path.of(nativeCatalinaBase);
	}

	public void ensureInitialized(String catalinaHome) throws IOException {
		Path home = Path.of(catalinaHome);
		Path base = getNativeCatalinaBase();
		log.info("[ensureNativeBase] 初始化 CATALINA_BASE={} (HOME={})", base, home);

		for (String dir : BASE_DIRS) {
			Files.createDirectories(base.resolve(dir));
		}
		xmlConfiguratorService.copyFromHome(home, base);
		log.info("[ensureNativeBase] 就緒，server.xml 將寫入 {}", base.resolve("conf/server.xml"));
	}

}
