package com.tmam.dto;

public record NginxStatusView(
		boolean enabled,
		boolean available,
		boolean running,
		String executable,
		String configPath,
		String locationsFragment,
		int listenPort,
		String message) {
}
