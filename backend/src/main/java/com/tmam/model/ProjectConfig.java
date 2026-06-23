package com.tmam.model;

public class ProjectConfig {

	private String name;
	private String displayName;
	private String warPath;
	private String contextPath;
	private PortConfig ports;
	private String jvmOpts;
	private boolean enabled = true;
	private String catalinaHome;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getWarPath() {
		return warPath;
	}

	public void setWarPath(String warPath) {
		this.warPath = warPath;
	}

	public String getContextPath() {
		return contextPath;
	}

	public void setContextPath(String contextPath) {
		this.contextPath = contextPath;
	}

	public PortConfig getPorts() {
		return ports;
	}

	public void setPorts(PortConfig ports) {
		this.ports = ports;
	}

	public String getJvmOpts() {
		return jvmOpts;
	}

	public void setJvmOpts(String jvmOpts) {
		this.jvmOpts = jvmOpts;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getCatalinaHome() {
		return catalinaHome;
	}

	public void setCatalinaHome(String catalinaHome) {
		this.catalinaHome = catalinaHome;
	}

}
