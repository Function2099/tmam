package com.tmam.dto;

import com.tmam.model.InstanceStatus;
import com.tmam.model.TomcatServiceType;

public record TomcatServiceView(
		String name,
		String displayName,
		TomcatServiceType type,
		String address,
		int port,
		boolean enabled,
		InstanceStatus status,
		String pathPrefix,
		String docBase,
		String publicUrl,
		boolean proxyStripPrefix) {
}
