package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TomcatStartupOptimizerTest {

	@Test
	void optimizeServerHeaderAddsStartStopThreads() {
		String header = """
				<?xml version="1.0"?>
				<Server port="8005" shutdown="SHUTDOWN">
				  <Listener className="org.apache.catalina.startup.VersionLoggerListener" />
				""";

		String optimized = TomcatStartupOptimizer.optimizeServerHeader(header);

		assertTrue(optimized.contains("startStopThreads=\"0\""));
		assertTrue(optimized.contains("port=\"8005\""));
		assertTrue(optimized.contains("shutdown=\"SHUTDOWN\""));
	}

	@Test
	void optimizeServerHeaderReplacesExistingStartStopThreads() {
		String header = "<Server port=\"8005\" shutdown=\"SHUTDOWN\" startStopThreads=\"1\">";

		String optimized = TomcatStartupOptimizer.optimizeServerHeader(header);

		assertTrue(optimized.contains("startStopThreads=\"0\""));
		assertFalse(optimized.contains("startStopThreads=\"1\""));
		assertEquals(1, optimized.split("startStopThreads", -1).length - 1);
	}

	@Test
	void optimizeServiceFragmentAddsHostThreadsAndJarScanner() {
		String fragment = """
				<Service name="Portal">
				  <Host name="Portal" unpackWARs="true" autoDeploy="true">
				    <Context path="" docBase="D:\\\\web" reloadable="true">
				      <Parameter name="tmam.online" value="true" override="false"/>
				    </Context>
				  </Host>
				</Service>
				""";

		String optimized = TomcatStartupOptimizer.optimizeServiceFragment(fragment);

		assertTrue(optimized.contains("startStopThreads=\"0\""));
		assertTrue(optimized.contains("autoDeploy=\"false\""));
		assertTrue(optimized.contains("deployOnStartup=\"false\""));
		assertFalse(optimized.contains("autoDeploy=\"true\""));
		assertTrue(optimized.contains("<JarScanner"));
		assertTrue(optimized.contains("tldSkip=\"*.jar\""));
		assertTrue(optimized.contains("pluggabilitySkip=\"*.jar\""));
		assertTrue(optimized.contains("name=\"tmam.online\""));
	}

	@Test
	void optimizeServiceFragmentDoesNotDuplicateJarScanner() {
		String fragment = """
				<Service name="Portal">
				  <Host name="Portal">
				    <Context path="" docBase="web">
				      <JarScanner scanManifest="false"/>
				    </Context>
				  </Host>
				</Service>
				""";

		String optimized = TomcatStartupOptimizer.optimizeServiceFragment(fragment);

		assertEquals(1, optimized.split("<JarScanner", -1).length - 1);
		assertTrue(optimized.contains("startStopThreads=\"0\""));
	}

	@Test
	void optimizeServiceFragmentExpandsSelfClosingContext() {
		String fragment = """
				<Service name="Portal">
				  <Host name="Portal">
				    <Context path="" docBase="web" reloadable="true"/>
				  </Host>
				</Service>
				""";

		String optimized = TomcatStartupOptimizer.optimizeServiceFragment(fragment);

		assertTrue(optimized.contains("<JarScanner"));
		assertFalse(optimized.contains("<Context path=\"\" docBase=\"web\" reloadable=\"true\"/>"));
		assertTrue(optimized.contains("</Context>"));
	}

}
