package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeTomcatEnvironmentServiceTest {

	@TempDir
	Path tempDir;

	private NativeTomcatEnvironmentService service;

	@BeforeEach
	void setUp() {
		service = new NativeTomcatEnvironmentService(tempDir.resolve("instances").toString());
	}

	@Test
	void getCatalinaBaseUsesInstallDirectory() {
		Path catalinaHome = tempDir.resolve("tomcat-home");
		assertEquals(catalinaHome.toAbsolutePath().normalize(),
				service.getCatalinaBase(catalinaHome.toString()));
	}

	@Test
	void ensureInitializedDoesNotCreateShadowCatalinaBase() throws Exception {
		Path catalinaHome = tempDir.resolve("tomcat-home");
		Files.createDirectories(catalinaHome.resolve("conf"));
		Files.writeString(catalinaHome.resolve("conf/server.xml"), "<Server/>");

		service.ensureInitialized("default", catalinaHome.toString());
		service.ensureInitialized("default", catalinaHome.toString());

		assertTrue(Files.isDirectory(catalinaHome.resolve("conf")));
		assertTrue(Files.notExists(tempDir.resolve("instances/default/catalina-base")));
	}

	@Test
	void ensureInitializedRequiresConfDirectory() {
		Path catalinaHome = tempDir.resolve("empty-home");
		assertThrows(IOException.class,
				() -> service.ensureInitialized("default", catalinaHome.toString()));
	}

	@Test
	void ensureInitializedAllowsReadOnlyConf() throws Exception {
		Path catalinaHome = tempDir.resolve("readonly-home");
		Path serverXml = catalinaHome.resolve("conf/server.xml");
		Files.createDirectories(serverXml.getParent());
		Files.writeString(serverXml, "<Server/>");
		assertTrue(serverXml.toFile().setReadOnly());

		service.ensureInitialized("default", catalinaHome.toString());
		assertThrows(java.nio.file.AccessDeniedException.class,
				() -> service.ensureWritable("default", catalinaHome.toString()));
	}

	@Test
	void wrapWriteFailureTurnsAccessDeniedIntoAdminHint() {
		Path serverXml = tempDir.resolve("tomcat-home/conf/server.xml");
		IOException wrapped = service.wrapWriteFailure(serverXml,
				new java.nio.file.AccessDeniedException(serverXml.toString()));
		assertTrue(wrapped instanceof java.nio.file.AccessDeniedException);
		assertTrue(wrapped.getMessage().contains(NativeTomcatEnvironmentService.ADMIN_REQUIRED_HINT)
				|| ((java.nio.file.AccessDeniedException) wrapped).getReason()
						.contains(NativeTomcatEnvironmentService.ADMIN_REQUIRED_HINT));
	}

	@Test
	void invalidateAllowsReinitialization() throws Exception {
		Path catalinaHome = tempDir.resolve("tomcat-home");
		Files.createDirectories(catalinaHome.resolve("conf"));
		Files.writeString(catalinaHome.resolve("conf/server.xml"), "<Server/>");

		service.ensureInitialized("default", catalinaHome.toString());
		service.invalidate("default");
		service.ensureInitialized("default", catalinaHome.toString());

		assertTrue(Files.isDirectory(catalinaHome.resolve("conf")));
	}

}
