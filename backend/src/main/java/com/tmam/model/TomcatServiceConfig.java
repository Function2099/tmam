package com.tmam.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TomcatServiceConfig {

	private String name;
	private TomcatServiceType type = TomcatServiceType.LEGACY_IP;
	private String address;
	private int port;
	private boolean enabled = true;
	private String displayName;
	private String pathPrefix;
	private String docBase;
	private boolean proxyStripPrefix = false;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public TomcatServiceType getType() {
		return type;
	}

	public void setType(TomcatServiceType type) {
		this.type = type;
	}

	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getPathPrefix() {
		return pathPrefix;
	}

	public void setPathPrefix(String pathPrefix) {
		this.pathPrefix = pathPrefix;
	}

	public String getDocBase() {
		return docBase;
	}

	public void setDocBase(String docBase) {
		this.docBase = docBase;
	}

	public boolean isProxyStripPrefix() {
		return proxyStripPrefix;
	}

	public void setProxyStripPrefix(boolean proxyStripPrefix) {
		this.proxyStripPrefix = proxyStripPrefix;
	}

	@JsonIgnore
	public boolean isPathProxy() {
		return type == TomcatServiceType.PATH_PROXY;
	}

	@JsonIgnore
	public boolean isLegacyIp() {
		return type == null || type == TomcatServiceType.LEGACY_IP;
	}

}
