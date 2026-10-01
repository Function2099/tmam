package com.tmam.dto;

import java.util.List;

import com.tmam.model.TomcatServiceType;

public record TomcatServiceCreateRequest(
		TomcatServiceType type,
		String name,
		String displayName,
		String pathPrefix,
		String docBase,
		String address,
		Integer port,
		Boolean enabled,
		Boolean proxyStripPrefix,
		Boolean online,
		List<String> legacyPaths,
		String indexPage) {
}
