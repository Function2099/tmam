package com.tmam.dto;

import java.util.List;

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
		boolean proxyStripPrefix,
		boolean online,
		boolean userCreated,
		List<String> legacyPaths,
		String indexPage) {

	public TomcatServiceView(
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
		this(name, displayName, type, address, port, enabled, status, pathPrefix, docBase, publicUrl,
				proxyStripPrefix, false, false, List.of(), null);
	}

	public TomcatServiceView(
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
			boolean proxyStripPrefix,
			boolean online) {
		this(name, displayName, type, address, port, enabled, status, pathPrefix, docBase, publicUrl,
				proxyStripPrefix, online, false, List.of(), null);
	}

	public TomcatServiceView(
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
			boolean proxyStripPrefix,
			boolean online,
			boolean userCreated) {
		this(name, displayName, type, address, port, enabled, status, pathPrefix, docBase, publicUrl,
				proxyStripPrefix, online, userCreated, List.of(), null);
	}

}
