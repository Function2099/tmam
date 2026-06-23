package com.tmam.dto;

public record NginxStatusView(
		boolean enabled,
		boolean available,
		String executable,
		String configPath,
		String locationsFragment,
		int listenPort,
		String message) {
}
