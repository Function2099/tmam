package com.tmam.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tmam.util.CatalinaHomeNormalizer;

@Service
public class NativeTomcatEnvironmentService {

	private static final Logger log = LoggerFactory.getLogger(NativeTomcatEnvironmentService.class);

	private static final List<String> BASE_DIRS = List.of("conf", "logs", "temp", "work", "webapps");

	private final String instancesRoot;
	private final XmlConfiguratorService xmlConfiguratorService;
	private final Set<String> initializedKeys = ConcurrentHashMap.newKeySet();

	public NativeTomcatEnvironmentService(@Value("${tmam.instances-root}") String instancesRoot,
			XmlConfiguratorService xmlConfiguratorService) {
		this.instancesRoot = instancesRoot;
		this.xmlConfiguratorService = xmlConfiguratorService;
	}

	public Path getInstanceRoot(String instanceId) {
		return Path.of(instancesRoot, instanceId);
	}

	public Path getCatalinaBase(String instanceId) {
		return getInstanceRoot(instanceId).resolve("catalina-base");
	}

	public Path getFragmentsDir(String instanceId) {
		return getInstanceRoot(instanceId).resolve("server-fragments");
	}

	public Path getBackupDir(String instanceId) {
		return getInstanceRoot(instanceId).resolve("backups");
	}

	public void ensureInitialized(String instanceId, String catalinaHome) throws IOException {
		Path home = Path.of(catalinaHome);
		Path base = getCatalinaBase(instanceId);
		String cacheKey = cacheKey(instanceId, catalinaHome);
		if (initializedKeys.contains(cacheKey) && Files.isDirectory(base.resolve("conf"))) {
			return;
		}

		log.info("[ensureInitialized] instance={}, CATALINA_BASE={} (HOME={})", instanceId, base, home);
		try {
			for (String dir : BASE_DIRS) {
				Files.createDirectories(base.resolve(dir));
			}
			xmlConfiguratorService.copyFromHome(home, base);
			initializedKeys.add(cacheKey);
			log.info("[ensureInitialized] 就緒，server.xml 將寫入 {}", base.resolve("conf/server.xml"));
		}
		catch (IOException ex) {
			initializedKeys.remove(cacheKey);
			throw ex;
		}
	}

	public void invalidate(String instanceId) {
		initializedKeys.removeIf(key -> key.startsWith(instanceId + "|"));
	}

	private static String cacheKey(String instanceId, String catalinaHome) {
		return instanceId + "|" + CatalinaHomeNormalizer.comparisonKey(catalinaHome);
	}

}
