package com.projectbrain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChunkerTest {

	@Test
	void shortTextIsOneTrimmedChunk() {
		assertThat(Chunker.split("  Users can book a table.  \n")).containsExactly("Users can book a table.");
	}

	@Test
	void blankTextHasNoChunks() {
		assertThat(Chunker.split(" \n\n \t ")).isEmpty();
		assertThat(Chunker.split(null)).isEmpty();
	}

	@Test
	void headingStartsANewChunkWithoutOverlap() {
		List<String> chunks = Chunker.split("# Booking\nUsers can book a table.\n\n# Payments\nUsers pay online.");

		assertThat(chunks).containsExactly("# Booking\nUsers can book a table.", "# Payments\nUsers pay online.");
	}

	@Test
	void smallParagraphsArePackedTogether() {
		assertThat(Chunker.split("First point.\n\nSecond point.\n\nThird point."))
			.containsExactly("First point.\n\nSecond point.\n\nThird point.");
	}

	@Test
	void nextChunkRepeatsTheEndOfThePreviousOne() {
		String first = words("alpha", 120);
		String second = words("bravo", 120);

		List<String> chunks = Chunker.split(first + "\n\n" + second);

		assertThat(chunks).hasSize(2);
		assertThat(chunks.get(0)).isEqualTo(first);
		assertThat(chunks.get(1)).endsWith(second).startsWith("alpha");
		int overlap = chunks.get(1).length() - second.length();
		assertThat(overlap).isBetween(100, 160);
	}

	@Test
	void longParagraphIsSplitIntoChunksTheAiServiceAccepts() {
		String paragraph = words("charlie", 900);
		String unbroken = "x".repeat(5000);

		List<String> chunks = Chunker.split(paragraph + "\n\n" + unbroken);

		assertThat(chunks).hasSizeGreaterThan(5).allSatisfy(chunk -> {
			assertThat(chunk).isNotBlank();
			assertThat(chunk.length()).isLessThanOrEqualTo(2000);
		});
		assertThat(String.join("", chunks).replace(" ", "")).contains("x".repeat(900));
	}

	private static String words(String word, int count) {
		return String.join(" ", java.util.Collections.nCopies(count, word));
	}

}
