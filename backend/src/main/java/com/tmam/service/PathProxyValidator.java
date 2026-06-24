package com.tmam.service;

import java.util.Collection;
import java.util.Map;

import com.tmam.model.TmamConfig;
import com.tmam.model.TomcatInstanceConfig;
import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;

public final class PathProxyValidator {

	private PathProxyValidator() {
	}

	public static String normalizePathPrefix(String pathPrefix) {
		if (pathPrefix == null || pathPrefix.isBlank()) {
			throw new IllegalArgumentException("路徑前綴不可為空");
		}
		String normalized = pathPrefix.trim();
		if (!normalized.startsWith("/")) {
			normalized = "/" + normalized;
		}
		while (normalized.length() > 1 && normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	public static void validateName(String name, Collection<TomcatServiceConfig> existing, String excludeName) {
		if (name == null || !name.matches("[A-Za-z0-9_\\-]+")) {
			throw new IllegalArgumentException("系統名稱僅允許英數、底線與連字號");
		}
		for (TomcatServiceConfig service : existing) {
			if (service.getName().equals(name) && !service.getName().equals(excludeName)) {
				throw new IllegalArgumentException("系統名稱已存在: " + name);
			}
		}
	}

	public static void validateNameAcrossInstances(String name, TmamConfig config, String instanceId,
			String excludeName) {
		validateName(name, servicesInInstance(config, instanceId), excludeName);
		for (Map.Entry<String, TomcatInstanceConfig> entry : config.getTomcatInstances().entrySet()) {
			if (entry.getKey().equals(instanceId)) {
				continue;
			}
			for (TomcatServiceConfig service : entry.getValue().getServices().values()) {
				if (service.getName().equals(name)) {
					throw new IllegalArgumentException("系統名稱已存在於其他 Tomcat 實例: " + name);
				}
			}
		}
	}

	public static void validatePathPrefix(String pathPrefix, Collection<TomcatServiceConfig> existing,
			String excludeName) {
		validatePathPrefixInternal(pathPrefix, existing, excludeName);
	}

	public static void validatePathPrefixAcrossInstances(String pathPrefix, TmamConfig config, String instanceId,
			String excludeName) {
		validatePathPrefixInternal(pathPrefix, servicesInInstance(config, instanceId), excludeName);
		for (TomcatInstanceConfig instance : config.getTomcatInstances().values()) {
			validatePathPrefixInternal(pathPrefix, instance.getServices().values(), excludeName);
		}
	}

	private static void validatePathPrefixInternal(String pathPrefix, Collection<TomcatServiceConfig> existing,
			String excludeName) {
		String normalized = normalizePathPrefix(pathPrefix);
		for (TomcatServiceConfig service : existing) {
			if (!service.isPathProxy() || service.getName().equals(excludeName)) {
				continue;
			}
			String other = normalizePathPrefix(service.getPathPrefix());
			if (normalized.equals(other) || normalized.startsWith(other + "/") || other.startsWith(normalized + "/")) {
				throw new IllegalArgumentException("路徑前綴與既有系統衝突: " + pathPrefix);
			}
		}
	}

	public static void validateDocBase(String docBase) {
		if (docBase == null || docBase.isBlank()) {
			throw new IllegalArgumentException("webapp 目錄不可為空");
		}
		if (!java.nio.file.Files.isDirectory(java.nio.file.Path.of(docBase.trim()))) {
			throw new IllegalArgumentException("webapp 目錄不存在: " + docBase);
		}
	}

	public static void validateNotLegacy(TomcatServiceConfig service) {
		if (service == null) {
			throw new IllegalArgumentException("未知 Service");
		}
		if (service.isLegacyIp() && !service.isUserCreated()) {
			throw new IllegalArgumentException("無法修改匯入的 IP 型 Service: " + service.getName());
		}
	}

	public static void validateModifiable(TomcatServiceConfig service) {
		if (service == null) {
			throw new IllegalArgumentException("未知 Service");
		}
	}

	public static void validateDeletable(TomcatInstanceConfig instance, String name, TomcatServiceConfig service) {
		if (service == null) {
			throw new IllegalArgumentException("未知 Service");
		}
		if (instance.getServices().size() <= 1) {
			throw new IllegalArgumentException("至少需要保留一個 Service");
		}
		if (!service.isEnabled()) {
			return;
		}
		long otherEnabled = instance.getServices().values().stream()
				.filter(TomcatServiceConfig::isEnabled)
				.filter(s -> !s.getName().equals(name))
				.count();
		if (otherEnabled < 1) {
			throw new IllegalArgumentException("至少需要保留一個啟用的 Service");
		}
		if (service.isLegacyIp()) {
			boolean hasOtherLegacy = instance.getServices().values().stream()
					.anyMatch(s -> s.isLegacyIp() && !s.getName().equals(name));
			if (hasOtherLegacy) {
				long otherEnabledLegacy = instance.getServices().values().stream()
						.filter(TomcatServiceConfig::isLegacyIp)
						.filter(TomcatServiceConfig::isEnabled)
						.filter(s -> !s.getName().equals(name))
						.count();
				if (otherEnabledLegacy < 1) {
					throw new IllegalArgumentException("至少需要保留一個啟用的 IP 型 Service");
				}
			}
		}
	}

	public static void validateLegacyIp(String address, int port) {
		if (address == null || address.isBlank()) {
			throw new IllegalArgumentException("IP 位址不可為空");
		}
		if (port <= 0 || port > 65535) {
			throw new IllegalArgumentException("Port 無效: " + port);
		}
	}

	public static void validateNotReservedName(String name, String pathGatewayServiceName) {
		if (pathGatewayServiceName.equals(name)) {
			throw new IllegalArgumentException("系統名稱不可使用保留名稱: " + name);
		}
	}

	public static String contextPathForTomcat(String pathPrefix) {
		return normalizePathPrefix(pathPrefix);
	}

	public static String nginxLocationPrefix(String pathPrefix) {
		return normalizePathPrefix(pathPrefix) + "/";
	}

	public static Collection<TomcatServiceConfig> allServices(TmamConfig config) {
		return config.getTomcatInstances().values().stream()
				.flatMap(instance -> instance.getServices().values().stream())
				.toList();
	}

	private static Collection<TomcatServiceConfig> servicesInInstance(TmamConfig config, String instanceId) {
		TomcatInstanceConfig instance = config.getTomcatInstances().get(instanceId);
		if (instance == null) {
			return java.util.List.of();
		}
		return instance.getServices().values();
	}

}
