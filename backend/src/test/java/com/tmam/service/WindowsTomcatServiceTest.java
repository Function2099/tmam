package com.tmam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class WindowsTomcatServiceTest {

	@Test
	void matchesHomeWhenBinaryLivesInInstallDir() {
		Path home = Path.of("C:/Program Files/apache-tomcat-9.0.115");
		assertTrue(WindowsTomcatService.matchesHome(
				"\"C:\\Program Files\\apache-tomcat-9.0.115\\bin\\Tomcat9.exe\" //RS//Tomcat9",
				home));
	}

	@Test
	void alreadyRunningIsNotAnAdminError() {
		String output = "[SC] StartService 失敗 1056:\n\n服務的實例已在執行中。";
		assertTrue(WindowsTomcatService.indicatesAlreadyRunning(output));
		assertFalse(WindowsTomcatService.indicatesAccessDenied(output));
		String message = WindowsTomcatService.describeFailure("啟動", "Tomcat9", output);
		assertTrue(message.contains("1056"));
		assertFalse(message.contains("系統管理員"));
	}

	@Test
	void accessDeniedStillAsksForAdministrator() {
		String output = "[SC] StartService FAILED 5:\n\nAccess is denied.";
		assertTrue(WindowsTomcatService.indicatesAccessDenied(output));
		assertFalse(WindowsTomcatService.indicatesAlreadyRunning(output));
		assertTrue(WindowsTomcatService.describeFailure("啟動", "Tomcat9", output).contains("系統管理員"));
	}

	@Test
	void notStartedStopIsRecognized() {
		assertTrue(WindowsTomcatService.indicatesNotStarted(
				"[SC] ControlService 失敗 1062:\n\n服務尚未啟動。"));
		assertEquals(false, WindowsTomcatService.indicatesAlreadyRunning(
				"[SC] StartService 失敗 1060:\n\n指定的服務未安裝。"));
	}

	@Test
	void doesNotMatchDifferentInstall() {
		assertFalse(WindowsTomcatService.matchesHome(
				"\"C:\\Program Files\\apache-tomcat-9.0.115\\bin\\Tomcat9.exe\" //RS//Tomcat9",
				Path.of("C:/Program Files/apache-tomcat-10.1.41")));
	}

}
