package com.tmam.dto;

import com.tmam.model.PortConfig;

public record ProjectCreateRequest(
		String name,
		String displayName,
		PortConfig ports,
		String warPath,
		String contextPath,
		String jvmOpts) {
}
