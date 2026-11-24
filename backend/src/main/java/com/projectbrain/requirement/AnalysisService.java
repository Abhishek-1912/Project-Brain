package com.projectbrain.requirement;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.requirement.AiRunStore.RunView;
import com.projectbrain.requirement.AiServiceClient.ClarificationAnswer;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class AnalysisService {

	private static final int MAX_ANSWERS = 30;

	private static final int MAX_QUESTION_CHARS = 1000;

	private static final List<String> SOURCED_SECTIONS = List.of("features", "users");

	private static final List<String> OPTIONAL_QUOTE_SECTIONS = List.of("gaps", "risks");

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final AiRunStore runStore;

	private final JsonMapper json;

	AnalysisService(OrgTx orgTx, JdbcClient jdbc, AiRunStore runStore, JsonMapper json) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.runStore = runStore;
		this.json = json;
	}

	AnalysisResponse latest(UUID orgId, UUID documentId) {
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			requireDocument(orgId, documentId);
			Optional<AnalysisView> analysis = jdbc.sql("""
					SELECT id, analysis_version, result::text AS result, created_at
					FROM brain.requirement_analyses
					WHERE organization_id = :orgId AND document_id = :documentId
					ORDER BY analysis_version DESC
					LIMIT 1
					""")
				.param("orgId", orgId)
				.param("documentId", documentId)
				.query((row, rowNumber) -> new AnalysisView(row.getObject("id", UUID.class),
						row.getInt("analysis_version"), json.readTree(row.getString("result")),
						row.getObject("created_at", OffsetDateTime.class)))
				.optional();
			List<QuestionView> questions = analysis.map(found -> questions(orgId, found.id())).orElse(List.of());
			return new AnalysisResponse(analysis.orElse(null), questions,
					runStore.latest(orgId, documentId, "ANALYSIS").orElse(null),
					runStore.latest(orgId, documentId, "STORIES").orElse(null));
		});
	}

	/** Must run inside an organization transaction. */
	AnalysisInput loadInput(UUID orgId, UUID documentId) {
		String text = documentText(orgId, documentId);
		Map<String, String> answers = new LinkedHashMap<>();
		jdbc.sql("""
				SELECT q.question, q.answer
				FROM brain.clarification_questions q
				JOIN brain.requirement_analyses a ON a.organization_id = q.organization_id AND a.id = q.analysis_id
				WHERE q.organization_id = :orgId AND a.document_id = :documentId AND q.status = 'ANSWERED'
				ORDER BY a.analysis_version DESC, q.position
				""")
			.param("orgId", orgId)
			.param("documentId", documentId)
			.query((row, rowNumber) -> Map.entry(row.getString("question"), row.getString("answer")))
			.list()
			.forEach(entry -> answers.putIfAbsent(entry.getKey(), entry.getValue()));
		List<ClarificationAnswer> limited = answers.entrySet()
			.stream()
			.limit(MAX_ANSWERS)
			.map(entry -> new ClarificationAnswer(entry.getKey(), entry.getValue()))
			.toList();
		return new AnalysisInput(text, limited);
	}

	/** Must run inside an organization transaction, so a bad result saves nothing. */
	void save(UUID orgId, UUID documentId, UUID runId, UUID actor, JsonNode generated) {
		List<JsonNode> questions = validate(generated);
		ObjectNode result = withVerifiedQuotes(generated, documentText(orgId, documentId));
		int version = jdbc.sql("""
				SELECT COALESCE(MAX(analysis_version), 0) + 1 FROM brain.requirement_analyses
				WHERE organization_id = :orgId AND document_id = :documentId
				""")
			.param("orgId", orgId)
			.param("documentId", documentId)
			.query(Integer.class)
			.single();
		UUID analysisId = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO brain.requirement_analyses
				    (id, organization_id, document_id, analysis_version, ai_run_id, result, created_by)
				VALUES (:id, :orgId, :documentId, :version, :runId, CAST(:result AS jsonb), :actor)
				""")
			.param("id", analysisId)
			.param("orgId", orgId)
			.param("documentId", documentId)
			.param("version", version)
			.param("runId", runId)
			.param("result", json.writeValueAsString(result))
			.param("actor", actor)
			.update();
		for (int position = 0; position < questions.size(); position++) {
			JsonNode question = questions.get(position);
			jdbc.sql("""
					INSERT INTO brain.clarification_questions (id, organization_id, analysis_id, position, question, reason)
					VALUES (:id, :orgId, :analysisId, :position, :question, :reason)
					""")
				.param("id", UUID.randomUUID())
				.param("orgId", orgId)
				.param("analysisId", analysisId)
				.param("position", position)
				.param("question", question.get("question").asString().strip())
				.param("reason", question.get("reason").asString().strip())
				.update();
		}
	}

	private List<QuestionView> questions(UUID orgId, UUID analysisId) {
		return jdbc.sql("""
				SELECT id, position, question, reason, status, answer, version
				FROM brain.clarification_questions
				WHERE organization_id = :orgId AND analysis_id = :analysisId
				ORDER BY position
				""")
			.param("orgId", orgId)
			.param("analysisId", analysisId)
			.query((row, rowNumber) -> new QuestionView(row.getObject("id", UUID.class), row.getInt("position"),
					row.getString("question"), row.getString("reason"), row.getString("status"),
					row.getString("answer"), row.getLong("version")))
			.list();
	}

	/** Must run inside an organization transaction. Returns the latest analysis in the AI service's input format. */
	StoryBasis storyBasis(UUID orgId, UUID documentId) {
		return jdbc.sql("""
				SELECT id, result::text AS result FROM brain.requirement_analyses
				WHERE organization_id = :orgId AND document_id = :documentId
				ORDER BY analysis_version DESC
				LIMIT 1
				""")
			.param("orgId", orgId)
			.param("documentId", documentId)
			.query((row, rowNumber) -> {
				UUID id = row.getObject("id", UUID.class);
				return new StoryBasis(id, forAiService(orgId, id, json.readTree(row.getString("result"))));
			})
			.optional()
			.orElseThrow(() -> new ConflictException("Analyze the document before generating stories."));
	}

	private JsonNode forAiService(UUID orgId, UUID analysisId, JsonNode stored) {
		ObjectNode payload = (ObjectNode) stored.deepCopy();
		for (String section : List.of("features", "users", "gaps", "risks")) {
			for (JsonNode item : payload.get(section)) {
				((ObjectNode) item).remove("sourceVerified");
			}
		}
		var questions = payload.putArray("questions");
		for (QuestionView question : questions(orgId, analysisId)) {
			questions.addObject().put("question", question.question()).put("reason", question.reason());
		}
		return payload;
	}

	void requireDocument(UUID orgId, UUID documentId) {
		boolean exists = jdbc.sql("""
				SELECT EXISTS (
				    SELECT 1 FROM brain.requirement_documents WHERE organization_id = :orgId AND id = :id
				)
				""")
			.param("orgId", orgId)
			.param("id", documentId)
			.query(Boolean.class)
			.single();
		if (!exists) {
			throw new NotFoundException("Requirement document not found.");
		}
	}

	private String documentText(UUID orgId, UUID documentId) {
		return jdbc.sql("""
				SELECT extracted_text FROM brain.requirement_documents
				WHERE organization_id = :orgId AND id = :id
				""")
			.param("orgId", orgId)
			.param("id", documentId)
			.query(String.class)
			.optional()
			.orElseThrow(() -> new NotFoundException("Requirement document not found."));
	}

	private static List<JsonNode> validate(JsonNode generated) {
		if (generated == null || !generated.isObject() || !isText(generated.get("summary"))) {
			throw new InvalidAiOutputException();
		}
		List<String> sections = new ArrayList<>(SOURCED_SECTIONS);
		sections.addAll(OPTIONAL_QUOTE_SECTIONS);
		for (String section : sections) {
			JsonNode items = generated.get(section);
			if (items == null || !items.isArray()) {
				throw new InvalidAiOutputException();
			}
			for (JsonNode item : items) {
				if (!item.isObject() || !isText(item.get("text"))) {
					throw new InvalidAiOutputException();
				}
			}
		}
		JsonNode questions = generated.get("questions");
		if (questions == null || !questions.isArray()) {
			throw new InvalidAiOutputException();
		}
		List<JsonNode> result = new ArrayList<>();
		for (JsonNode question : questions) {
			if (!question.isObject() || !isQuestionText(question.get("question"))
					|| !isQuestionText(question.get("reason"))) {
				throw new InvalidAiOutputException();
			}
			result.add(question);
		}
		return result;
	}

	private static boolean isText(JsonNode node) {
		return node != null && node.isString() && !node.asString().isBlank();
	}

	private static boolean isQuestionText(JsonNode node) {
		return isText(node) && node.asString().strip().length() <= MAX_QUESTION_CHARS;
	}

	/** Our own check replaces anything the model claimed about its quotes. */
	private static ObjectNode withVerifiedQuotes(JsonNode generated, String documentText) {
		ObjectNode result = (ObjectNode) generated.deepCopy();
		result.remove("questions");
		for (String section : SOURCED_SECTIONS) {
			for (JsonNode item : result.get(section)) {
				ObjectNode object = (ObjectNode) item;
				object.put("sourceVerified", QuoteVerifier.isVerified(quote(object), documentText));
			}
		}
		for (String section : OPTIONAL_QUOTE_SECTIONS) {
			for (JsonNode item : result.get(section)) {
				ObjectNode object = (ObjectNode) item;
				object.remove("sourceVerified");
				String quote = quote(object);
				if (quote != null) {
					object.put("sourceVerified", QuoteVerifier.isVerified(quote, documentText));
				}
			}
		}
		return result;
	}

	private static String quote(JsonNode item) {
		JsonNode quote = item.get("sourceQuote");
		return quote != null && quote.isString() ? quote.asString() : null;
	}

	record AnalysisInput(String text, List<ClarificationAnswer> answers) {
	}

	record StoryBasis(UUID analysisId, JsonNode analysis) {
	}

	record AnalysisView(UUID id, int version, JsonNode result, OffsetDateTime createdAt) {
	}

	record QuestionView(UUID id, int position, String question, String reason, String status, String answer,
			long version) {
	}

	record AnalysisResponse(AnalysisView analysis, List<QuestionView> questions, RunView latestRun,
			RunView latestStoryRun) {
	}

}
