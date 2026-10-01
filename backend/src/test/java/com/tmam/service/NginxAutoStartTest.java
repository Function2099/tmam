package com.tmam.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class NginxAutoStartTest {

	@Test
	void skipsWhenDisabled() {
		TomcatInstanceManagementService management = mock(TomcatInstanceManagementService.class);
		NginxAutoStart autoStart = new NginxAutoStart(management, false);

		autoStart.onApplicationReady();

		verify(management, never()).ensureNginxRunning();
	}

	@Test
	void startsWhenEnabled() {
		TomcatInstanceManagementService management = mock(TomcatInstanceManagementService.class);
		NginxAutoStart autoStart = new NginxAutoStart(management, true);

		autoStart.onApplicationReady();

		verify(management).ensureNginxRunning();
	}

}
