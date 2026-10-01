package com.tmam.dto;

import java.util.List;

public record TomcatServiceUpdateRequest(
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
