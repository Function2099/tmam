package com.tmam.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class LogFileReader {

	private static final int MAX_TAIL_BYTES = 512 * 1024;
	private static final int READ_RETRIES = 3;

	private LogFileReader() {
	}

	public static List<String> readLastLines(Path logFile, int lines) throws IOException {
		if (!Files.exists(logFile) || lines <= 0) {
			return List.of();
		}
		IOException lastError = null;
		for (int attempt = 0; attempt < READ_RETRIES; attempt++) {
			try {
				return doReadLastLines(logFile, lines);
			}
			catch (IOException e) {
				lastError = e;
				if (attempt < READ_RETRIES - 1) {
					try {
						Thread.sleep(50L);
					}
					catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						throw new IOException("讀取日誌時被中斷", interrupted);
					}
				}
			}
		}
		throw lastError;
	}

	private static List<String> doReadLastLines(Path logFile, int lines) throws IOException {
		long size = Files.size(logFile);
		if (size == 0) {
			return List.of();
		}

		long start = Math.max(0, size - MAX_TAIL_BYTES);
		byte[] chunk = new byte[(int) (size - start)];
		try (SeekableByteChannel channel = Files.newByteChannel(logFile, StandardOpenOption.READ)) {
			channel.position(start);
			ByteBuffer buffer = ByteBuffer.wrap(chunk);
			while (buffer.hasRemaining()) {
				if (channel.read(buffer) < 0) {
					break;
				}
			}
			int readLength = chunk.length - buffer.remaining();
			String text = new String(chunk, 0, readLength, StandardCharsets.UTF_8);
			return splitLastLines(text, start > 0, lines);
		}
	}

	private static List<String> splitLastLines(String text, boolean skipPartialFirstLine, int lines) {
		if (skipPartialFirstLine) {
			int firstNewline = text.indexOf('\n');
			if (firstNewline >= 0) {
				text = text.substring(firstNewline + 1);
			}
		}

		List<String> allLines = new ArrayList<>(Arrays.asList(text.split("\\R", -1)));
		while (!allLines.isEmpty() && allLines.get(allLines.size() - 1).isEmpty()) {
			allLines.remove(allLines.size() - 1);
		}
		if (allLines.size() <= lines) {
			return allLines;
		}
		return new ArrayList<>(allLines.subList(allLines.size() - lines, allLines.size()));
	}

}
