package com.projectbrain.requirement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectbrain.requirement.ArchitectureService.ArchitectureVersion;
import com.projectbrain.requirement.ArchitectureService.StoryLink;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ArchitectureMarkdownTest {

	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	void markdownEscapesTableBreakingText() {
		String risky = "a | b `c` # d\\nnext *bold* <b>";
		ArchitectureVersion version = new ArchitectureVersion(UUID.randomUUID(), UUID.randomUUID(), 3, "DRAFT", null, null,
				json.readTree("""
						{"summary":"# Not a heading\\nSecond line","components":[],
						 "techStack":[{"area":"Backend","choice":"%s","reason":"r"}],"decisions":[],"risks":[]}
						""".formatted(risky)),
				json.readTree("{\"tables\":[]}"), json.readTree("""
						{"endpoints":[{"method":"GET","path":"/a|b","summary":"%s","requestFields":[],"responseFields":[],
						  "roles":[],"storyIds":[]}]}
						""".formatted(risky)),
				List.of(), List.<StoryLink>of(), null, OffsetDateTime.now(), OffsetDateTime.now(), 0, false, false);

		String markdown = ArchitectureMarkdown.render(version, List.of(), Map.of());

		assertThat(markdown).contains("a \\| b \\`c\\` \\# d next \\*bold\\* \\<b\\>");
		assertThat(markdown).contains("/a\\|b");
		assertThat(markdown).contains("\\# Not a heading");
		assertThat(markdown.lines().filter(line -> line.startsWith("| GET")).count()).isEqualTo(1);
		assertThat(markdown.lines().filter(line -> line.startsWith("| Backend")).count()).isEqualTo(1);
	}

}
