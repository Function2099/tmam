package com.tmam.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

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

	public static void validatePathPrefix(String pathPrefix, Collection<TomcatServiceConfig> existing,
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
		Path path = Path.of(docBase.trim());
		if (!Files.isDirectory(path)) {
			throw new IllegalArgumentException("webapp 目錄不存在: " + docBase);
		}
	}

	public static void validateNotLegacy(TomcatServiceConfig service) {
		if (service == null) {
			throw new IllegalArgumentException("未知 Service");
		}
		if (service.isLegacyIp()) {
			throw new IllegalArgumentException("無法修改既有 IP 型 Service: " + service.getName());
		}
	}

	public static Set<String> reservedNames(String pathGatewayServiceName) {
		Set<String> reserved = new HashSet<>();
		reserved.add(pathGatewayServiceName);
		return reserved;
	}

	public static void validateNotReservedName(String name, String pathGatewayServiceName) {
		if (reservedNames(pathGatewayServiceName).contains(name)) {
			throw new IllegalArgumentException("系統名稱不可使用保留名稱: " + name);
		}
	}

	public static String contextPathForTomcat(String pathPrefix) {
		return normalizePathPrefix(pathPrefix);
	}

	public static String nginxLocationPrefix(String pathPrefix) {
		return normalizePathPrefix(pathPrefix) + "/";
	}

}