package com.tmam.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFileReaderTest {

	@TempDir
	Path tempDir;

	@Test
	void readLastLinesReturnsTail() throws Exception {
		Path logFile = tempDir.resolve("app.log");
		Files.writeString(logFile, "line1\nline2\nline3\n");

		List<String> lines = LogFileReader.readLastLines(logFile, 2);

		assertEquals(List.of("line2", "line3"), lines);
	}

	@Test
	void readLastLinesToleratesInvalidUtf8Bytes() throws Exception {
		Path logFile = tempDir.resolve("app.log");
		byte[] content = new byte[] {
				'l', 'i', 'n', 'e', '1', '\n',
				(byte) 0xFF, (byte) 0xFE, '\n',
				'l', 'i', 'n', 'e', '3', '\n',
		};
		Files.write(logFile, content);

		List<String> lines = LogFileReader.readLastLines(logFile, 10);

		assertEquals(3, lines.size());
		assertEquals("line1", lines.get(0));
		assertTrue(lines.get(1).length() > 0);
		assertEquals("line3", lines.get(2));
	}

	@Test
	void readLastLinesFromLargeTailWindow() throws Exception {
		Path logFile = tempDir.resolve("large.log");
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < 5000; i++) {
			builder.append("entry-").append(i).append('\n');
		}
		Files.writeString(logFile, builder.toString(), StandardCharsets.UTF_8);

		List<String> lines = LogFileReader.readLastLines(logFile, 3);

		assertEquals(List.of("entry-4997", "entry-4998", "entry-4999"), lines);
	}

}
