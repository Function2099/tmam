package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TomcatDiscoveryServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void recognizesTomcatHomeLayout() throws Exception {
		Path home = tempDir.resolve("apache-tomcat-9.0.test");
		Files.createDirectories(home.resolve("bin"));
		Files.createDirectories(home.resolve("conf"));
		Files.writeString(home.resolve("bin/catalina.bat"), "@echo off");
		Files.writeString(home.resolve("conf/server.xml"), "<Server/>");

		TomcatDiscoveryService discovery = new TomcatDiscoveryService();
		assertTrue(discovery.isTomcatHome(home));
	}

}
