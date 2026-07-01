package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalinaHomeResolverTest {

	@TempDir
	Path tempDir;

	@Test
	void prefersExplicitPath() throws Exception {
		Path home = createTomcatHome("explicit-tomcat");
		CatalinaHomeResolver resolver = new CatalinaHomeResolver(new TomcatDiscoveryService(), "");

		assertEquals(home.toString(), resolver.resolve(home.toString()));
	}

	@Test
	void usesConfiguredDefaultWhenExplicitMissing() throws Exception {
		CatalinaHomeResolver resolver = new CatalinaHomeResolver(
				new TomcatDiscoveryService(), "D:/configured-tomcat");

		assertEquals("D:/configured-tomcat", resolver.resolve(null));
	}

	@Test
	void discoversTomcatFromFilesystem() {
		TomcatDiscoveryService discovery = new TomcatDiscoveryService() {
			@Override
			public java.util.List<com.tmam.dto.TomcatDiscoveryView> discover() {
				return java.util.List.of(new com.tmam.dto.TomcatDiscoveryView(
						"D:/discovered-tomcat",
						"discovered-tomcat",
						"9.0"));
			}
		};
		CatalinaHomeResolver resolver = new CatalinaHomeResolver(discovery, "");

		assertEquals("D:/discovered-tomcat", resolver.resolve(null));
	}

	@Test
	void resolvePathThrowsWhenMissing() {
		TomcatDiscoveryService discovery = new TomcatDiscoveryService() {
			@Override
			public java.util.List<com.tmam.dto.TomcatDiscoveryView> discover() {
				return java.util.List.of();
			}
		};
		CatalinaHomeResolver resolver = new CatalinaHomeResolver(discovery, "");

		assertThrows(IllegalStateException.class, () -> resolver.resolvePath(null));
	}

	private Path createTomcatHome(String name) throws Exception {
		Path home = tempDir.resolve(name);
		Files.createDirectories(home.resolve("bin"));
		Files.createDirectories(home.resolve("conf"));
		Files.writeString(home.resolve("bin/catalina.bat"), "@echo off");
		Files.writeString(home.resolve("conf/server.xml"), "<Server/>");
		return home;
	}

}
