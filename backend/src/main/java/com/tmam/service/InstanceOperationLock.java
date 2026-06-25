package com.tmam.service;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Service;

import com.tmam.model.StartResult;

@Service
public class InstanceOperationLock {

	private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

	public <T> T withLock(String instanceId, Callable<T> action) throws Exception {
		ReentrantLock lock = locks.computeIfAbsent(instanceId, id -> new ReentrantLock());
		if (!lock.tryLock(0, TimeUnit.SECONDS)) {
			throw new InstanceOperationBusyException(instanceId);
		}
		try {
			return action.call();
		}
		finally {
			lock.unlock();
		}
	}

	public void withLock(String instanceId, Runnable action) throws Exception {
		withLock(instanceId, () -> {
			action.run();
			return null;
		});
	}

	public StartResult busyResult(String instanceId) {
		return StartResult.failure(instanceId, "操作進行中，請稍後再試");
	}

	public static class InstanceOperationBusyException extends RuntimeException {

		private final String instanceId;

		public InstanceOperationBusyException(String instanceId) {
			super("Instance operation in progress: " + instanceId);
			this.instanceId = instanceId;
		}

		public String getInstanceId() {
			return instanceId;
		}
	}

}
