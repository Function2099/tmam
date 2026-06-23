package com.tmam.dto;

public record TomcatServiceCreateRequest(
		String name,
		String displayName,
		String pathPrefix,
		String docBase,
		Boolean enabled,
		Boolean proxyStripPrefix) {
}
