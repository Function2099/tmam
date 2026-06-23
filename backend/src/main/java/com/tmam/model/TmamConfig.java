package com.tmam.model;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TmamConfig {

	private String version = "1.0.0";
	private String mode = "native";
	private String catalinaHome;
	private DefaultConfig defaults = new DefaultConfig();
	private Map<String, ProjectConfig> projects = new LinkedHashMap<>();
	private Map<String, TomcatServiceConfig> services = new LinkedHashMap<>();

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public String getCatalinaHome() {
		return catalinaHome;
	}

	public void setCatalinaHome(String catalinaHome) {
		this.catalinaHome = catalinaHome;
	}

	public DefaultConfig getDefaults() {
		return defaults;
	}

	public void setDefaults(DefaultConfig defaults) {
		this.defaults = defaults;
	}

	public Map<String, ProjectConfig> getProjects() {
		return projects;
	}

	public void setProjects(Map<String, ProjectConfig> projects) {
		this.projects = projects;
	}

	public String getMode() {
		return mode;
	}

	public void setMode(String mode) {
		this.mode = mode;
	}

	public Map<String, TomcatServiceConfig> getServices() {
		return services;
	}

	public void setServices(Map<String, TomcatServiceConfig> services) {
		this.services = services;
	}

	public boolean isNativeMode() {
		return mode == null || mode.isBlank() || "native".equalsIgnoreCase(mode);
	}

}
