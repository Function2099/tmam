package com.tmam.dto;

public record TomcatServiceUpdateRequest(
		String displayName,
		String pathPrefix,
		String docBase,
		String address,
		Integer port,
		Boolean enabled,
		Boolean proxyStripPrefix) {
}
