package com.tmam.model;

public class DefaultConfig {

	private String jvmOpts = "-Xms256m -Xmx512m -server";
	private int startupTimeoutSec = 30;

	public String getJvmOpts() {
		return jvmOpts;
	}

	public void setJvmOpts(String jvmOpts) {
		this.jvmOpts = jvmOpts;
	}

	public int getStartupTimeoutSec() {
		return startupTimeoutSec;
	}

	public void setStartupTimeoutSec(int startupTimeoutSec) {
		this.startupTimeoutSec = startupTimeoutSec;
	}

}
