package com.tmam.dto;

public record TomcatInstanceView(
		String id,
		String displayName,
		String catalinaHome,
		int shutdownPort,
		int gatewayPort,
		int enabledServiceCount,
		int totalServiceCount,
		String status) {
}
