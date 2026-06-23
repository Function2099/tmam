package com.tmam.dto;

public record TomcatServiceUpdateRequest(
		String displayName,
		String pathPrefix,
		String docBase,
		Boolean enabled,
		Boolean proxyStripPrefix) {
}
