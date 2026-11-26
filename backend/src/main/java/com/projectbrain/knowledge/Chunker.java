package com.projectbrain.knowledge;

import java.util.ArrayList;
import java.util.List;

/** Splits text along headings and paragraphs into chunks the AI service can embed (2,000 characters at most). */
final class Chunker {

	static final int TARGET = 1000;

	static final int OVERLAP = 150;

	private static final String SEPARATOR = "\n\n";

	private Chunker() {
	}

	static List<String> split(String text) {
		List<String> chunks = new ArrayList<>();
		if (text == null) {
			return chunks;
		}
		StringBuilder current = new StringBuilder();
		for (Block block : blocks(text)) {
			if (block.heading() && !current.isEmpty()) {
				// A new section starts fresh, so no overlap crosses a heading.
				chunks.add(current.toString());
				current.setLength(0);
			}
			for (String piece : pieces(block.text())) {
				if (current.isEmpty()) {
					current.append(piece);
				}
				else if (current.length() + SEPARATOR.length() + piece.length() <= TARGET) {
					current.append(SEPARATOR).append(piece);
				}
				else {
					String done = current.toString();
					chunks.add(done);
					current.setLength(0);
					String tail = tail(done);
					if (!tail.isEmpty()) {
						current.append(tail).append(SEPARATOR);
					}
					current.append(piece);
				}
			}
		}
		if (!current.isEmpty()) {
			chunks.add(current.toString());
		}
		return chunks;
	}

	private static List<Block> blocks(String text) {
		List<Block> blocks = new ArrayList<>();
		StringBuilder lines = new StringBuilder();
		boolean heading = false;
		for (String line : text.split("\\R", -1)) {
			boolean startsHeading = line.stripLeading().startsWith("#");
			if (line.isBlank() || startsHeading) {
				addBlock(blocks, lines, heading);
				lines.setLength(0);
				heading = startsHeading;
				if (line.isBlank()) {
					continue;
				}
			}
			if (!lines.isEmpty()) {
				lines.append('\n');
			}
			lines.append(line);
		}
		addBlock(blocks, lines, heading);
		return blocks;
	}

	private static void addBlock(List<Block> blocks, StringBuilder lines, boolean heading) {
		String value = lines.toString().strip();
		if (!value.isEmpty()) {
			blocks.add(new Block(value, heading));
		}
	}

	private static List<String> pieces(String text) {
		List<String> pieces = new ArrayList<>();
		String rest = text;
		while (rest.length() > TARGET) {
			int cut = Math.max(rest.lastIndexOf(' ', TARGET), rest.lastIndexOf('\n', TARGET));
			if (cut < TARGET / 2) {
				cut = TARGET;
			}
			pieces.add(rest.substring(0, cut).strip());
			rest = rest.substring(cut).strip();
		}
		if (!rest.isEmpty()) {
			pieces.add(rest);
		}
		return pieces;
	}

	/** The end of a chunk, starting at a word boundary, at most OVERLAP characters long. */
	private static String tail(String chunk) {
		if (chunk.length() <= OVERLAP) {
			return chunk;
		}
		int start = chunk.length() - OVERLAP;
		int space = chunk.indexOf(' ', start);
		return space < 0 ? chunk.substring(start).strip() : chunk.substring(space + 1).strip();
	}

	private record Block(String text, boolean heading) {
	}

}
