package com.projectbrain.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.identity.api.CurrentUserProvider;
import com.projectbrain.knowledge.KnowledgeAiClient.GeneratedAnswer;
import com.projectbrain.knowledge.KnowledgeAiClient.Passage;
import com.projectbrain.knowledge.KnowledgeAiClient.Usage;
import com.projectbrain.knowledge.KnowledgeSearch.Hit;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.shared.web.ServiceUnavailableException;
import com.projectbrain.shared.web.UnprocessableException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Holds no database transaction during AI calls, and shows an answer as found only with a valid citation. */
@Service
class KnowledgeAskService {

	static final String NOT_FOUND = "Not found in this project's knowledge.";

	private static final int MAX_QUESTION_CHARS = 500;

	private static final int DEFAULT_LIMIT = 10;

	private static final int MAX_LIMIT = 20;

	private static final int MAX_PASSAGES = 8;

	private static final int EXCERPT_CHARS = 300;

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final ProjectLookup projects;

	private final KnowledgeSearch search;

	private final KnowledgeAiClient ai;

	private final CurrentUserProvider currentUser;

	private final AuditLog auditLog;

	private final JsonMapper json;

	private final Semaphore asking = new Semaphore(2);

	KnowledgeAskService(OrgTx orgTx, JdbcClient jdbc, ProjectLookup projects, KnowledgeSearch search,
			KnowledgeAiClient ai, CurrentUserProvider currentUser, AuditLog auditLog, JsonMapper json) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.projects = projects;
		this.search = search;
		this.ai = ai;
		this.currentUser = currentUser;
		this.auditLog = auditLog;
		this.json = json;
	}

	SearchResult search(UUID orgId, UUID projectId, String q, Integer limit) {
		String query = clean(q, "Search text");
		int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
		requireAccess(orgId, projectId);
		float[] vector = embed(query);
		List<SearchItem> items = orgTx.inOrg(orgId, Permission.VIEW,
				() -> search.search(orgId, projectId, query, vector, size))
			.stream()
			.map(hit -> new SearchItem(hit.chunkId(), hit.sourceId(), hit.sourceType(), hit.title(), hit.content(),
					hit.score()))
			.toList();
		return new SearchResult(items);
	}

	AnswerView ask(UUID orgId, UUID projectId, String rawQuestion) {
		String question = clean(rawQuestion, "Question");
		UUID actor = currentUser.get().id();
		requireAccess(orgId, projectId);
		if (!asking.tryAcquire()) {
			throw new ServiceUnavailableException("Busy, try again.");
		}
		try {
			List<Hit> relevant;
			try {
				float[] vector = embedOrThrow(question);
				relevant = orgTx.inOrg(orgId, Permission.VIEW,
						() -> search.search(orgId, projectId, question, vector, MAX_PASSAGES))
					.stream()
					.filter(Hit::relevant)
					.toList();
				if (relevant.isEmpty()) {
					return save(orgId, projectId, actor, question, NOT_FOUND, false, List.of(), null, null);
				}
				List<Passage> passages = new ArrayList<>();
				for (int i = 0; i < relevant.size(); i++) {
					passages.add(new Passage(i + 1, relevant.get(i).content()));
				}
				GeneratedAnswer generated = ai.answer(question, passages);
				List<Citation> citations = citations(generated, relevant);
				if (citations.isEmpty()) {
					return save(orgId, projectId, actor, question, NOT_FOUND, false, List.of(), generated.usage(),
							null);
				}
				return save(orgId, projectId, actor, question, generated.answer().strip(), true, citations,
						generated.usage(), null);
			}
			catch (KnowledgeAiException ex) {
				save(orgId, projectId, actor, question, null, false, List.of(), null, ex.code());
				throw new ServiceUnavailableException("The AI service is unavailable. Try again shortly.");
			}
		}
		finally {
			asking.release();
		}
	}

	/** Keeps only citations of passages that were sent, in the model's order, without repeats. */
	private static List<Citation> citations(GeneratedAnswer generated, List<Hit> relevant) {
		if (!generated.found() || generated.answer() == null || generated.answer().isBlank()) {
			return List.of();
		}
		Set<Integer> numbers = new LinkedHashSet<>();
		for (Integer number : generated.citations()) {
			if (number != null && number >= 1 && number <= relevant.size()) {
				numbers.add(number);
			}
		}
		List<Citation> citations = new ArrayList<>();
		for (int number : numbers) {
			Hit hit = relevant.get(number - 1);
			String excerpt = hit.content().length() <= EXCERPT_CHARS ? hit.content()
					: hit.content().substring(0, EXCERPT_CHARS - 1) + "…";
			citations.add(new Citation(number, hit.chunkId(), hit.sourceId(), hit.sourceType(), hit.title(), excerpt));
		}
		return citations;
	}

	private AnswerView save(UUID orgId, UUID projectId, UUID actor, String question, String answer, boolean found,
			List<Citation> citations, Usage usage, String errorCode) {
		UUID id = UUID.randomUUID();
		orgTx.inOrg(orgId, Permission.VIEW, () -> {
			jdbc.sql("""
					INSERT INTO brain.knowledge_answers
					    (id, organization_id, project_id, asked_by, question, answer, found, citations, provider, model,
					     prompt_version, input_tokens, output_tokens, error_code)
					VALUES (:id, :orgId, :projectId, :actor, :question, :answer, :found, CAST(:citations AS jsonb),
					        :provider, :model, :promptVersion, :inputTokens, :outputTokens, :errorCode)
					""")
				.param("id", id)
				.param("orgId", orgId)
				.param("projectId", projectId)
				.param("actor", actor)
				.param("question", question)
				.param("answer", answer, java.sql.Types.VARCHAR)
				.param("found", found)
				.param("citations", json.writeValueAsString(citations))
				.param("provider", usage == null ? null : usage.provider(), java.sql.Types.VARCHAR)
				.param("model", usage == null ? null : usage.model(), java.sql.Types.VARCHAR)
				.param("promptVersion", usage == null ? null : usage.promptVersion(), java.sql.Types.VARCHAR)
				.param("inputTokens", usage == null ? 0 : usage.inputTokens())
				.param("outputTokens", usage == null ? 0 : usage.outputTokens())
				.param("errorCode", errorCode, java.sql.Types.VARCHAR)
				.update();
			auditLog.record(orgId, "KNOWLEDGE_ASKED", "KNOWLEDGE_ANSWER", id,
					errorCode == null ? Map.of("found", found) : Map.of("found", found, "errorCode", errorCode));
			return null;
		});
		return new AnswerView(id, question, answer, found, citations);
	}

	private void requireAccess(UUID orgId, UUID projectId) {
		orgTx.inOrg(orgId, Permission.VIEW, () -> projects.requireStatus(orgId, projectId));
	}

	private float[] embed(String text) {
		try {
			return embedOrThrow(text);
		}
		catch (KnowledgeAiException ex) {
			throw new ServiceUnavailableException("The AI service is unavailable. Try again shortly.");
		}
	}

	private float[] embedOrThrow(String text) {
		return ai.embed(List.of(text)).get(0);
	}

	private static String clean(String value, String label) {
		String cleaned = value == null ? "" : value.strip();
		if (cleaned.isEmpty() || cleaned.length() > MAX_QUESTION_CHARS) {
			throw new UnprocessableException(label + " must be between 1 and 500 characters.");
		}
		return cleaned;
	}

	record SearchItem(UUID chunkId, UUID sourceId, String sourceType, String title, String content, double score) {
	}

	record SearchResult(List<SearchItem> items) {
	}

	record Citation(int number, UUID chunkId, UUID sourceId, String sourceType, String title, String excerpt) {
	}

	record AnswerView(UUID id, String question, String answer, boolean found, List<Citation> citations) {
	}

	record AskRequest(String question) {
	}

}
