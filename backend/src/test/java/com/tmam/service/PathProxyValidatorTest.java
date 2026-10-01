package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tmam.model.TomcatInstanceConfig;
import com.tmam.model.TomcatServiceConfig;
import com.tmam.model.TomcatServiceType;

class PathProxyValidatorTest {

	@TempDir
	Path tempDir;

	@Test
	void normalizePathPrefixAddsLeadingSlash() {
		assertEquals("/new-system", PathProxyValidator.normalizePathPrefix("new-system"));
		assertEquals("/portal", PathProxyValidator.normalizePathPrefix("/portal/"));
	}

	@Test
	void normalizeIndexPageStripsLeadingSlash() {
		assertEquals("index_Login.jsp", PathProxyValidator.normalizeIndexPage("/index_Login.jsp"));
		assertEquals("", PathProxyValidator.normalizeIndexPage("  "));
	}

	@Test
	void validateDocBaseRequiresExistingDirectory() throws Exception {
		Path docBase = tempDir.resolve("web");
		Files.createDirectories(docBase);
		PathProxyValidator.validateDocBase(docBase.toString());
		assertThrows(IllegalArgumentException.class, () -> PathProxyValidator.validateDocBase("D:/missing-webapp"));
	}

	@Test
	void validatePathPrefixDetectsConflict() {
		TomcatServiceConfig existing = new TomcatServiceConfig();
		existing.setName("A");
		existing.setType(TomcatServiceType.PATH_PROXY);
		existing.setPathPrefix("/portal");

		assertThrows(IllegalArgumentException.class,
				() -> PathProxyValidator.validatePathPrefix("/portal", List.of(existing), null));
	}

	@Test
	void normalizeLegacyPathsTrimsAndDeduplicates() {
		assertEquals(List.of("/images", "/Modules"),
				PathProxyValidator.normalizeLegacyPaths(List.of(" images ", "/Modules/", "/images", "")));
	}

	@Test
	void normalizeLegacyPathsRejectsRoot() {
		assertThrows(IllegalArgumentException.class,
				() -> PathProxyValidator.normalizeLegacyPaths(List.of("/")));
	}

	@Test
	void validateLegacyPathsAllowsSharingAcrossServices() {
		com.tmam.model.TmamConfig config = new com.tmam.model.TmamConfig();
		TomcatInstanceConfig instance = new TomcatInstanceConfig();
		TomcatServiceConfig other = new TomcatServiceConfig();
		other.setName("Other");
		other.setDisplayName("後台");
		other.setType(TomcatServiceType.PATH_PROXY);
		other.setPathPrefix("/other");
		other.setLegacyPaths(new java.util.ArrayList<>(List.of("/images")));
		instance.getServices().put(other.getName(), other);
		config.getTomcatInstances().put(TomcatInstanceConfig.DEFAULT_ID, instance);

		PathProxyValidator.validateLegacyPathsAcrossInstances(List.of("/images"), config, "Mine");
		PathProxyValidator.validateLegacyPathsAcrossInstances(List.of("/template"), config, "Mine");
		assertThrows(IllegalArgumentException.class, () -> PathProxyValidator
				.validateLegacyPathsAcrossInstances(List.of("/other"), config, "Mine"));
	}

	@Test
	void validateLegacyPathsRejectsOwnPathPrefix() {
		com.tmam.model.TmamConfig config = new com.tmam.model.TmamConfig();
		TomcatInstanceConfig instance = new TomcatInstanceConfig();
		TomcatServiceConfig self = new TomcatServiceConfig();
		self.setName("Mine");
		self.setType(TomcatServiceType.PATH_PROXY);
		self.setPathPrefix("/web");
		instance.getServices().put(self.getName(), self);
		config.getTomcatInstances().put(TomcatInstanceConfig.DEFAULT_ID, instance);

		assertThrows(IllegalArgumentException.class, () -> PathProxyValidator
				.validateLegacyPathsAcrossInstances(List.of("/web"), config, "Mine"));
	}

	@Test
	void validateDeletableAllowsRemovingDisabledLegacyService() {
		TomcatInstanceConfig instance = new TomcatInstanceConfig();
		TomcatServiceConfig keep = legacyService("Portal", true);
		TomcatServiceConfig remove = legacyService("Loc", false);
		instance.getServices().put(keep.getName(), keep);
		instance.getServices().put(remove.getName(), remove);

		PathProxyValidator.validateDeletable(instance, remove.getName(), remove);
	}

	@Test
	void validateDeletableBlocksLastService() {
		TomcatInstanceConfig instance = new TomcatInstanceConfig();
		TomcatServiceConfig only = legacyService("Portal", true);
		instance.getServices().put(only.getName(), only);

		assertThrows(IllegalArgumentException.class,
				() -> PathProxyValidator.validateDeletable(instance, only.getName(), only));
	}

	@Test
	void validateDeletableBlocksRemovingLastEnabledLegacyIp() {
		TomcatInstanceConfig instance = new TomcatInstanceConfig();
		TomcatServiceConfig enabled = legacyService("Portal", true);
		TomcatServiceConfig disabled = legacyService("Loc", false);
		instance.getServices().put(enabled.getName(), enabled);
		instance.getServices().put(disabled.getName(), disabled);

		assertThrows(IllegalArgumentException.class,
				() -> PathProxyValidator.validateDeletable(instance, enabled.getName(), enabled));
	}

	private static TomcatServiceConfig legacyService(String name, boolean enabled) {
		TomcatServiceConfig service = new TomcatServiceConfig();
		service.setName(name);
		service.setType(TomcatServiceType.LEGACY_IP);
		service.setEnabled(enabled);
		service.setAddress("127.0.0.1");
		service.setPort(8080);
		return service;
	}
}
