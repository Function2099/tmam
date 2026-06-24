package com.tmam.model;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TmamConfig {

	public static final String VERSION_2 = "2.0.0";

	private String version = VERSION_2;
	private String mode = "native";
	private String catalinaHome;
	private DefaultConfig defaults = new DefaultConfig();
	private Map<String, ProjectConfig> projects = new LinkedHashMap<>();
	private Map<String, TomcatServiceConfig> services = new LinkedHashMap<>();
	private Map<String, TomcatInstanceConfig> tomcatInstances = new LinkedHashMap<>();

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

	public Map<String, TomcatInstanceConfig> getTomcatInstances() {
		return tomcatInstances;
	}

	public void setTomcatInstances(Map<String, TomcatInstanceConfig> tomcatInstances) {
		this.tomcatInstances = tomcatInstances;
	}

	public boolean isNativeMode() {
		return mode == null || mode.isBlank() || "native".equalsIgnoreCase(mode);
	}

	public boolean isV2() {
		return VERSION_2.equals(version);
	}

	@JsonIgnore
	public TomcatInstanceConfig getDefaultInstance() {
		return tomcatInstances.get(TomcatInstanceConfig.DEFAULT_ID);
	}

	@JsonIgnore
	public TomcatInstanceConfig requireInstance(String instanceId) {
		TomcatInstanceConfig instance = tomcatInstances.get(instanceId);
		if (instance == null) {
			throw new IllegalArgumentException("未知 Tomcat 實例: " + instanceId);
		}
		return instance;
	}

}
