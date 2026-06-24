package com.tmam.model;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TomcatInstanceConfig {

	public static final String DEFAULT_ID = "default";

	private String id;
	private String displayName;
	private String catalinaHome;
	private int shutdownPort = 8005;
	private int gatewayPort = 8080;
	private String jvmOpts;
	private Map<String, TomcatServiceConfig> services = new LinkedHashMap<>();

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getCatalinaHome() {
		return catalinaHome;
	}

	public void setCatalinaHome(String catalinaHome) {
		this.catalinaHome = catalinaHome;
	}

	public int getShutdownPort() {
		return shutdownPort;
	}

	public void setShutdownPort(int shutdownPort) {
		this.shutdownPort = shutdownPort;
	}

	public int getGatewayPort() {
		return gatewayPort;
	}

	public void setGatewayPort(int gatewayPort) {
		this.gatewayPort = gatewayPort;
	}

	public String getJvmOpts() {
		return jvmOpts;
	}

	public void setJvmOpts(String jvmOpts) {
		this.jvmOpts = jvmOpts;
	}

	public Map<String, TomcatServiceConfig> getServices() {
		return services;
	}

	public void setServices(Map<String, TomcatServiceConfig> services) {
		this.services = services;
	}

}
