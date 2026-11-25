package com.projectbrain.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ArchitectureValidatorTest {

	private static final UUID STORY = UUID.randomUUID();

	private static final Map<String, UUID> KEYS = Map.of("S1", STORY);

	private final JsonMapper json = JsonMapper.builder().build();

	private final ArchitectureValidator validator = new ArchitectureValidator();

	private static final String DATA_MODEL = """
			{"tables":[
			  {"name":"customers","description":"People","columns":[
			    {"name":"id","type":"uuid","nullable":false,"description":"Key"}],
			   "relations":[],"storyRefs":["S1"]},
			  {"name":"bookings","description":"Reservations","columns":[
			    {"name":"id","type":"uuid","nullable":false,"description":"Key"},
			    {"name":"customer_id","type":"uuid","nullable":false,"description":"Who booked"}],
			   "relations":[{"column":"customer_id","referencesTable":"customers","referencesColumn":"id"}],
			   "storyRefs":["S1","S9"]}]}
			""";

	private static final String API = """
			{"endpoints":[
			  {"method":"POST","path":"/bookings","summary":"Book","requestFields":[
			    {"name":"partySize","type":"integer","required":true,"description":"People"}],
			   "responseFields":[],"roles":["Diner"],"storyRefs":["S1"]},
			  {"method":"GET","path":"/bookings","summary":"List","requestFields":[],"responseFields":[],
			   "roles":[],"storyRefs":[]}]}
			""";

	@Test
	void validDataModelMapsStoryRefsToIds() {
		JsonNode stored = validator.dataModel(json.readTree(DATA_MODEL), KEYS);

		assertThat(stored.get("tables").get(1).has("storyRefs")).isFalse();
		assertThat(stored.get("tables").get(1).get("storyIds").get(0).asString()).isEqualTo(STORY.toString());
	}

	@Test
	void unknownStoryRefsAreDropped() {
		JsonNode stored = validator.dataModel(json.readTree(DATA_MODEL), KEYS);

		assertThat(stored.get("tables").get(1).get("storyIds")).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "\"referencesTable\":\"customers\"|\"referencesTable\":\"clients\"",
			"\"referencesColumn\":\"id\"|\"referencesColumn\":\"uuid\"",
			"\"column\":\"customer_id\"|\"column\":\"owner_id\"" })
	void relationToUnknownTableOrColumnIsInvalid(String replacement) {
		String[] parts = replacement.split("\\|");

		assertThatThrownBy(() -> validator.dataModel(json.readTree(DATA_MODEL.replace(parts[0], parts[1])), KEYS))
			.isInstanceOf(InvalidAiOutputException.class);
	}

	@Test
	void duplicateTableColumnOrEndpointIsInvalid() {
		String duplicateTable = DATA_MODEL.replace("\"name\":\"bookings\"", "\"name\":\"customers\"");
		String duplicateColumn = DATA_MODEL.replace("\"name\":\"customer_id\"", "\"name\":\"id\"")
			.replace("\"column\":\"customer_id\"", "\"column\":\"id\"");
		String duplicateEndpoint = API.replace("\"method\":\"GET\"", "\"method\":\"POST\"");

		assertThatThrownBy(() -> validator.dataModel(json.readTree(duplicateTable), KEYS))
			.isInstanceOf(InvalidAiOutputException.class);
		assertThatThrownBy(() -> validator.dataModel(json.readTree(duplicateColumn), KEYS))
			.isInstanceOf(InvalidAiOutputException.class);
		assertThatThrownBy(() -> validator.apiDesign(json.readTree(duplicateEndpoint), KEYS))
			.isInstanceOf(InvalidAiOutputException.class);
	}

	@Test
	void badMethodPathOrColumnTypeIsInvalid() {
		assertThatThrownBy(() -> validator.apiDesign(json.readTree(API.replace("\"GET\"", "\"TRACE\"")), KEYS))
			.isInstanceOf(InvalidAiOutputException.class);
		assertThatThrownBy(() -> validator.apiDesign(json.readTree(API.replace("\"/bookings\",\"summary\":\"List\"",
				"\"bookings\",\"summary\":\"List\"")), KEYS))
			.isInstanceOf(InvalidAiOutputException.class);
		assertThatThrownBy(() -> validator.dataModel(json.readTree(DATA_MODEL.replace("\"type\":\"uuid\",\"nullable\":false,\"description\":\"Who",
				"\"type\":\"varchar\",\"nullable\":false,\"description\":\"Who")), KEYS))
			.isInstanceOf(InvalidAiOutputException.class);
		assertThatThrownBy(() -> validator.dataModel(json.readTree(DATA_MODEL.replace("\"name\":\"bookings\"",
				"\"name\":\"Bookings\"")), KEYS))
			.isInstanceOf(InvalidAiOutputException.class);
	}

	@Test
	void overviewNeedsSummaryAndLists() {
		String overview = """
				{"summary":"S","components":[],"techStack":[],"decisions":[],"risks":[]}
				""";

		assertThat(validator.overview(json.readTree(overview)).get("summary").asString()).isEqualTo("S");
		assertThatThrownBy(() -> validator.overview(json.readTree("{\"summary\":\"S\"}")))
			.isInstanceOf(InvalidAiOutputException.class);
	}

	@Test
	void editedSectionsKeepOnlyAllowedStoryIds() {
		UUID other = UUID.randomUUID();
		String edited = API.replace("\"storyRefs\":[\"S1\"]", "\"storyIds\":[\"" + STORY + "\",\"" + other + "\"]")
			.replace("\"storyRefs\":[]", "\"storyIds\":[]");

		JsonNode stored = validator.editedApiDesign(json.readTree(edited), Set.of(STORY));

		assertThat(stored.get("endpoints").get(0).get("storyIds")).hasSize(1);
	}

	@Test
	void storedSectionTurnsBackIntoStepForm() {
		JsonNode stored = validator.dataModel(json.readTree(DATA_MODEL), KEYS);

		JsonNode step = validator.toStepForm(stored, Map.of(STORY, "S4"));

		assertThat(step.get("tables").get(1).get("storyRefs").get(0).asString()).isEqualTo("S4");
		assertThat(step.get("tables").get(1).has("storyIds")).isFalse();
	}

}
