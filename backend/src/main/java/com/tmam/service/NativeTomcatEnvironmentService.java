package com.tmam.service;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
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

	static final String ADMIN_REQUIRED_HINT = "請以系統管理員執行 TMAM。";

	private final String instancesRoot;
	private final Set<String> initializedKeys = ConcurrentHashMap.newKeySet();

	public NativeTomcatEnvironmentService(@Value("${tmam.instances-root}") String instancesRoot) {
		this.instancesRoot = instancesRoot;
	}

	public Path getInstanceRoot(String instanceId) {
		return Path.of(instancesRoot, instanceId);
	}

	/**
	 * 執行時 CATALINA_BASE 與安裝目錄相同（in-place），log 與 server.xml 都寫在這裡。
	 */
	public Path getCatalinaBase(String catalinaHome) {
		if (catalinaHome == null || catalinaHome.isBlank()) {
			throw new IllegalArgumentException("Tomcat 安裝路徑不可為空");
		}
		return Path.of(catalinaHome.trim()).toAbsolutePath().normalize();
	}

	public Path getFragmentsDir(String instanceId) {
		return getInstanceRoot(instanceId).resolve("server-fragments");
	}

	public Path getBackupDir(String instanceId) {
		return getInstanceRoot(instanceId).resolve("backups");
	}

	public void ensureInitialized(String instanceId, String catalinaHome) throws IOException {
		Path home = getCatalinaBase(catalinaHome);
		Path conf = home.resolve("conf");
		String cacheKey = cacheKey(instanceId, catalinaHome);
		if (initializedKeys.contains(cacheKey) && Files.isDirectory(conf)) {
			return;
		}

		log.info("[ensureInitialized] instance={}, CATALINA_HOME={} (in-place BASE)", instanceId, home);
		if (!Files.isDirectory(conf)) {
			throw new IOException("找不到 Tomcat conf 目錄: " + conf);
		}
		initializedKeys.add(cacheKey);
		log.info("[ensureInitialized] 就緒，執行目錄 {}", home);
	}

	public void ensureWritable(String instanceId, String catalinaHome) throws IOException {
		ensureInitialized(instanceId, catalinaHome);
		Path home = getCatalinaBase(catalinaHome);
		Path conf = home.resolve("conf");
		Path serverXml = conf.resolve("server.xml");
		if (!isConfWritable(conf, serverXml)) {
			throw unwritable(Files.exists(serverXml) ? serverXml : conf);
		}
	}

	public AccessDeniedException unwritable(Path path) {
		return new AccessDeniedException(path.toString(), null,
				"無法寫入 " + path + "。" + ADMIN_REQUIRED_HINT);
	}

	public IOException wrapWriteFailure(Path path, IOException cause) {
		if (isPermissionFailure(cause)) {
			AccessDeniedException wrapped = unwritable(path);
			wrapped.initCause(cause);
			return wrapped;
		}
		return cause;
	}

	public void invalidate(String instanceId) {
		initializedKeys.removeIf(key -> key.startsWith(instanceId + "|"));
	}

	private static boolean isConfWritable(Path conf, Path serverXml) {
		if (!Files.isWritable(conf)) {
			return false;
		}
		return !Files.exists(serverXml) || Files.isWritable(serverXml);
	}

	private static boolean isPermissionFailure(IOException ex) {
		if (ex instanceof AccessDeniedException) {
			return true;
		}
		String message = ex.getMessage();
		if (message == null) {
			return false;
		}
		String lower = message.toLowerCase();
		return lower.contains("access") || lower.contains("denied") || lower.contains("permission");
	}

	private static String cacheKey(String instanceId, String catalinaHome) {
		return instanceId + "|" + CatalinaHomeNormalizer.comparisonKey(catalinaHome);
	}

}
