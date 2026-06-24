package com.tmam.dto;

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
		Boolean proxyStripPrefix) {
}
