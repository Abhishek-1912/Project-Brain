package com.projectbrain.requirement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import com.projectbrain.requirement.AiServiceClient.ArchitectureStepRequest;
import com.projectbrain.requirement.AiServiceClient.ArchitectureStoryInput;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.UnprocessableException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
class ArchitectureInputService {

	static final int MAX_STORIES = 200;

	static final int MAX_REQUEST_CHARACTERS = 200_000;

	/**
	 * Steps 2 and 3 add the overview and data model to the same request, so step 1 must leave room for them inside
	 * the AI service's 200,000-character limit.
	 */
	static final int MAX_STEP_ONE_CHARACTERS = 120_000;

	private final JdbcClient jdbc;

	private final JsonMapper json;

	private final ArchitectureValidator validator;

	ArchitectureInputService(JdbcClient jdbc, JsonMapper json, ArchitectureValidator validator) {
		this.jdbc = jdbc;
		this.json = json;
		this.validator = validator;
	}

	/** Must run inside an organization transaction. */
	ArchitectureInput load(UUID orgId, UUID projectId) {
		record StoryRow(UUID id, long version, ArchitectureStoryInput input) {
		}
		List<StoryRow> rows = jdbc.sql("""
				SELECT id, version, title, as_a, i_want, so_that, acceptance_criteria::text AS criteria, priority
				FROM brain.story_drafts
				WHERE organization_id = :orgId AND project_id = :projectId AND status IN ('APPROVED', 'CONVERTED')
				ORDER BY created_at, id
				""")
			.param("orgId", orgId)
			.param("projectId", projectId)
			.query((row, rowNumber) -> {
				List<String> criteria = new ArrayList<>();
				json.readTree(row.getString("criteria")).forEach(item -> criteria.add(item.asString()));
				return new StoryRow(row.getObject("id", UUID.class), row.getLong("version"),
						new ArchitectureStoryInput("S" + (rowNumber + 1), row.getString("title"), row.getString("as_a"),
								row.getString("i_want"), row.getString("so_that"), criteria, row.getString("priority")));
			})
			.list();
		if (rows.isEmpty()) {
			throw new ConflictException("Approve at least one user story before generating the architecture.");
		}
		if (rows.size() > MAX_STORIES) {
			throw new UnprocessableException("Architecture generation supports up to 200 approved stories.");
		}
		Map<String, UUID> idsByKey = new LinkedHashMap<>();
		Map<UUID, String> keysById = new HashMap<>();
		rows.forEach(row -> {
			idsByKey.put(row.input().key(), row.id());
			keysById.put(row.id(), row.input().key());
		});
		List<ArchitectureStoryInput> stories = rows.stream().map(StoryRow::input).toList();

		Optional<StartingVersion> start = startingVersion(orgId, projectId);
		List<String> comments = start.filter(version -> "REJECTED".equals(version.status()))
			.map(version -> rejectionComments(orgId, version.id()))
			.orElse(List.of());
		ObjectNode startingPoint0 = start.map(version -> {
			ObjectNode point = json.createObjectNode();
			point.put("status", version.status());
			point.set("overview", validator.toStepForm(version.overview(), keysById));
			point.set("dataModel", validator.toStepForm(version.dataModel(), keysById));
			point.set("apiDesign", validator.toStepForm(version.apiDesign(), keysById));
			ArrayNode list = point.putArray("rejectionComments");
			comments.forEach(list::add);
			return point;
		}).orElse(null);
		ObjectNode startingPoint = startingPoint0;

		if (size(stories, null) > MAX_STEP_ONE_CHARACTERS) {
			throw new UnprocessableException(
					"The approved stories are too long to generate an architecture in one run. Shorten or split them.");
		}
		if (startingPoint != null && size(stories, startingPoint) > MAX_STEP_ONE_CHARACTERS) {
			// A large earlier version must not block every future run, so the AI gets its outline instead.
			startingPoint = compact(startingPoint);
			if (size(stories, startingPoint) > MAX_STEP_ONE_CHARACTERS) {
				throw new UnprocessableException(
						"The approved stories and the previous architecture version are too large to combine in one run.");
			}
		}
		String fingerprint = fingerprint(rows.stream().map(row -> new StoryVersion(row.id(), row.version())).toList(),
				start.map(StartingVersion::id).orElse(null), start.map(StartingVersion::version).orElse(0L), comments,
				ArchitecturePrompts.VERSIONS);
		return new ArchitectureInput(stories, idsByKey, start.map(StartingVersion::id).orElse(null), startingPoint,
				fingerprint);
	}

	/** The same stories, starting point, and prompts always give the same value, so finished steps can be reused. */
	static String fingerprint(List<StoryVersion> stories, UUID startingVersionId, long startingVersion,
			List<String> rejectionComments, Map<String, String> promptVersions) {
		StringBuilder canonical = new StringBuilder("stories:");
		stories.stream()
			.sorted(Comparator.comparing(story -> story.id().toString()))
			.forEach(story -> canonical.append(story.id()).append('@').append(story.version()).append(';'));
		canonical.append("|start:").append(startingVersionId).append('@').append(startingVersion);
		canonical.append("|comments:");
		rejectionComments.forEach(comment -> canonical.append(comment.length()).append(':').append(comment));
		canonical.append("|prompts:");
		new TreeMap<>(promptVersions).forEach((step, version) -> canonical.append(step).append('=').append(version)
			.append(';'));
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private int size(List<ArchitectureStoryInput> stories, JsonNode startingPoint) {
		return json.writeValueAsString(
				ArchitectureRequestBody.of(json, new ArchitectureStepRequest(stories, startingPoint, null, null)))
			.length();
	}

	/** Keeps the summary, decisions, names, paths, and comments, and drops descriptions, columns, and fields. */
	private ObjectNode compact(ObjectNode full) {
		ObjectNode point = json.createObjectNode();
		point.put("status", full.get("status").asString());
		point.put("compacted", true);
		ObjectNode overview = point.putObject("overview");
		overview.put("summary", full.path("overview").path("summary").asString(""));
		ArrayNode decisions = overview.putArray("decisions");
		full.path("overview").path("decisions").forEach(decision -> decisions.addObject()
			.put("title", decision.path("title").asString(""))
			.put("decision", decision.path("decision").asString("")));
		ArrayNode tables = point.putObject("dataModel").putArray("tables");
		full.path("dataModel").path("tables").forEach(table -> {
			ObjectNode item = tables.addObject().put("name", table.path("name").asString(""));
			item.set("storyRefs", table.path("storyRefs"));
		});
		ArrayNode endpoints = point.putObject("apiDesign").putArray("endpoints");
		full.path("apiDesign").path("endpoints").forEach(endpoint -> {
			ObjectNode item = endpoints.addObject()
				.put("method", endpoint.path("method").asString(""))
				.put("path", endpoint.path("path").asString(""));
			item.set("storyRefs", endpoint.path("storyRefs"));
		});
		point.set("rejectionComments", full.path("rejectionComments"));
		return point;
	}

	private Optional<StartingVersion> startingVersion(UUID orgId, UUID projectId) {
		return jdbc.sql("""
				SELECT id, version, status, overview::text AS overview, data_model::text AS data_model,
				       api_design::text AS api_design
				FROM brain.architecture_versions
				WHERE organization_id = :orgId AND project_id = :projectId AND status IN ('APPROVED', 'REJECTED')
				ORDER BY version_number DESC
				LIMIT 1
				""")
			.param("orgId", orgId)
			.param("projectId", projectId)
			.query((row, rowNumber) -> new StartingVersion(row.getObject("id", UUID.class), row.getLong("version"),
					row.getString("status"), json.readTree(row.getString("overview")),
					json.readTree(row.getString("data_model")), json.readTree(row.getString("api_design"))))
			.optional();
	}

	private List<String> rejectionComments(UUID orgId, UUID versionId) {
		return jdbc.sql("""
				SELECT comment FROM brain.architecture_reviews
				WHERE organization_id = :orgId AND version_id = :versionId AND action = 'REJECTED'
				ORDER BY created_at, id
				""")
			.param("orgId", orgId)
			.param("versionId", versionId)
			.query(String.class)
			.list();
	}

	record StoryVersion(UUID id, long version) {
	}

	private record StartingVersion(UUID id, long version, String status, JsonNode overview, JsonNode dataModel,
			JsonNode apiDesign) {
	}

}
