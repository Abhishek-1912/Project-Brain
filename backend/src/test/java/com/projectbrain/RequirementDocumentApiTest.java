package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.projectbrain.requirement.AiServiceClient;
import com.projectbrain.requirement.AiServiceException;
import com.projectbrain.support.TeamApiTestBase;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RequirementDocumentApiTest.FakeAiConfig.class)
@TestPropertySource(properties = { "SUPABASE_URL=http://127.0.0.1:1", "APP_CORS_ALLOWED_ORIGINS=http://localhost:5173" })
class RequirementDocumentApiTest extends TeamApiTestBase {

	private static final String TEXT = "Private dummy requirement text";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private FakeAi ai;

	@BeforeEach
	void resetAi() {
		ai.failure = null;
		ai.filenames.clear();
	}

	@Test
	void ownerAndAdminCanUploadDummyDocument() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID admin = seedMember(org, "ADMIN");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID manager = seedJobRole(org, "Project Manager", true);

		String body = upload(owner, org, project, "true", null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("prd.md"))
			.andExpect(jsonPath("$.mediaType").value("text/markdown"))
			.andExpect(jsonPath("$.sizeBytes").value(TEXT.length()))
			.andExpect(jsonPath("$.characters").value(TEXT.length()))
			.andExpect(jsonPath("$.approverJobRoleId").value(manager.toString()))
			.andExpect(jsonPath("$.uploadedBy").value(owner.toString()))
			.andExpect(jsonPath("$.extractedText").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID document = UUID.fromString(JsonPath.read(body, "$.id"));
		upload(admin, org, project, "true", null).andExpect(status().isCreated());

		assertThat(countRows("""
				SELECT count(*) FROM brain.requirement_documents
				WHERE id = ? AND dummy_data_confirmed AND extracted_text = ? AND length(sha256) = 64
				""", document, TEXT)).isEqualTo(1);
		assertThat(countRows("""
				SELECT count(*) FROM brain.requirement_document_files
				WHERE document_id = ? AND content = ?
				""", document, TEXT.getBytes(StandardCharsets.UTF_8))).isEqualTo(1);
		assertThat(audits(org, document)).isEqualTo(1);
	}

	@Test
	void memberCanViewButCannotUpload() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID member = seedMember(org, "MEMBER");
		UUID viewer = seedMember(org, "VIEWER");
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID document = uploadedId(owner, org, project);

		upload(member, org, project, "true", null).andExpect(status().isForbidden());
		upload(viewer, org, project, "true", null).andExpect(status().isForbidden());
		assertThat(ai.filenames).hasSize(1);
		mvc.perform(get(listUrl(org, project)).with(as(viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(document.toString()))
			.andExpect(jsonPath("$.items[0].extractedText").doesNotExist());
		mvc.perform(get(detailUrl(org, document)).with(as(member)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(document.toString()))
			.andExpect(jsonPath("$.extractedText").doesNotExist());
	}

	@ParameterizedTest
	@ValueSource(strings = { "false", "" })
	void uploadRequiresDummyConfirmation(String confirmed) throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID project = seedProject(org, owner, "ACTIVE");

		upload(owner, org, project, confirmed, null).andExpect(status().isUnprocessableContent());

		assertThat(ai.filenames).isEmpty();
		assertThat(documents(org)).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "UNSUPPORTED_TYPE", "FILE_TOO_LARGE", "NO_TEXT", "TEXT_TOO_LONG", "CORRUPT_FILE" })
	void invalidFileErrorsStoreNothing(String code) throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID project = seedProject(org, owner, "ACTIVE");
		ai.failure = code;

		upload(owner, org, project, "true", null)
			.andExpect(status().isUnprocessableContent())
			.andExpect(content().string(Matchers.not(Matchers.containsString(code))));

		assertThat(documents(org)).isZero();
	}

	@Test
	void unavailableAiServiceReturnsSafeError() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID project = seedProject(org, owner, "ACTIVE");
		ai.failure = "SERVICE_UNAVAILABLE";

		upload(owner, org, project, "true", null).andExpect(status().isServiceUnavailable());

		assertThat(documents(org)).isZero();
	}

	@Test
	void fileOverTenMegabytesIsRejectedBeforeExtraction() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID project = seedProject(org, owner, "ACTIVE");
		MockMultipartFile large = new MockMultipartFile("file", "large.txt", "text/plain",
				new byte[10 * 1024 * 1024 + 1]);

		mvc.perform(multipart(listUrl(org, project)).file(large).param("dummyDataConfirmed", "true").with(as(owner)))
			.andExpect(status().isUnprocessableContent());

		assertThat(ai.filenames).isEmpty();
	}

	@Test
	void nulCharactersFromExtractionAreRemoved() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID project = seedProject(org, owner, "ACTIVE");
		MockMultipartFile file = new MockMultipartFile("file", "prd.txt", "text/plain",
				"Dummy\u0000 text".getBytes(StandardCharsets.UTF_8));

		mvc.perform(multipart(listUrl(org, project)).file(file).param("dummyDataConfirmed", "true").with(as(owner)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.characters").value(10));
	}

	@Test
	void filenamePathIsRemoved() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID project = seedProject(org, owner, "ACTIVE");
		MockMultipartFile file = new MockMultipartFile("file", "../../secret/prd.md", "text/plain",
				TEXT.getBytes(StandardCharsets.UTF_8));

		mvc.perform(multipart(listUrl(org, project)).file(file).param("dummyDataConfirmed", "true").with(as(owner)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("prd.md"));
	}

	@Test
	void approverRoleMustBeActiveInOrganization() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID project = seedProject(org, owner, "ACTIVE");
		UUID inactive = seedJobRole(org, "Legacy", false);
		UUID otherOwner = seedProfile();
		UUID otherRole = seedJobRole(seedOrg(otherOwner), "Backend", true);
		UUID qa = seedJobRole(org, "QA", true);

		upload(owner, org, project, "true", inactive).andExpect(status().isUnprocessableContent());
		upload(owner, org, project, "true", otherRole).andExpect(status().isUnprocessableContent());
		assertThat(ai.filenames).isEmpty();
		upload(owner, org, project, "true", qa)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.approverJobRoleId").value(qa.toString()));
	}

	@Test
	void noProjectManagerRoleLeavesApproverEmpty() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID project = seedProject(org, owner, "ACTIVE");

		upload(owner, org, project, "true", null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.approverJobRoleId").value(Matchers.nullValue()));
	}

	@Test
	void archivedProjectBlocksUpload() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		UUID project = seedProject(org, owner, "ARCHIVED");

		upload(owner, org, project, "true", null).andExpect(status().isConflict());

		assertThat(ai.filenames).isEmpty();
	}

	@Test
	void anotherOrganizationsDocumentReturns404() throws Exception {
		UUID ownerA = seedProfile();
		UUID orgA = seedOrg(ownerA);
		UUID projectA = seedProject(orgA, ownerA, "ACTIVE");
		UUID ownerB = seedProfile();
		UUID orgB = seedOrg(ownerB);
		UUID documentB = uploadedId(ownerB, orgB, seedProject(orgB, ownerB, "ACTIVE"));

		mvc.perform(get(detailUrl(orgA, documentB)).with(as(ownerA))).andExpect(status().isNotFound());
		mvc.perform(get(detailUrl(orgB, documentB)).with(as(ownerA))).andExpect(status().isNotFound());
		upload(ownerA, orgB, projectA, "true", null).andExpect(status().isNotFound());
	}

	@Test
	void auditDoesNotContainDocumentText() throws Exception {
		UUID owner = seedProfile();
		UUID org = seedOrg(owner);
		uploadedId(owner, org, seedProject(org, owner, "ACTIVE"));

		assertThat(countRows("""
				SELECT count(*) FROM brain.audit_events
				WHERE organization_id = ? AND action = 'REQUIREMENT_UPLOADED' AND metadata::text LIKE ?
				""", org, "%" + TEXT + "%")).isZero();
	}

	private UUID uploadedId(UUID user, UUID org, UUID project) throws Exception {
		String body = upload(user, org, project, "true", null)
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	private ResultActions upload(UUID user, UUID org, UUID project, String confirmed, UUID approver) throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "prd.md", "text/plain",
				TEXT.getBytes(StandardCharsets.UTF_8));
		var request = multipart(listUrl(org, project)).file(file).param("dummyDataConfirmed", confirmed).with(as(user));
		if (approver != null) {
			request.param("approverJobRoleId", approver.toString());
		}
		return mvc.perform(request);
	}

	private static String listUrl(UUID org, UUID project) {
		return ORGS + "/" + org + "/projects/" + project + "/requirements";
	}

	private static String detailUrl(UUID org, UUID document) {
		return ORGS + "/" + org + "/requirements/" + document;
	}

	private static long documents(UUID org) throws Exception {
		return countRows("SELECT count(*) FROM brain.requirement_documents WHERE organization_id = ?", org);
	}

	private static long audits(UUID org, UUID document) throws Exception {
		return countRows("""
				SELECT count(*) FROM brain.audit_events
				WHERE organization_id = ? AND action = 'REQUIREMENT_UPLOADED' AND entity_id = ?
				""", org, document);
	}

	static class FakeAi implements AiServiceClient {

		final List<String> filenames = new ArrayList<>();

		volatile String failure;

		@Override
		public ExtractedText extract(String filename, String contentType, byte[] content) {
			filenames.add(filename);
			if (failure != null) {
				throw new AiServiceException(failure);
			}
			String text = new String(content, StandardCharsets.UTF_8);
			return new ExtractedText(text, text.length());
		}

	}

	@TestConfiguration
	static class FakeAiConfig {

		@Bean
		@Primary
		FakeAi fakeAi() {
			return new FakeAi();
		}

	}

}
