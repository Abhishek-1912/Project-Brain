import re
from typing import Annotated, Any, Literal

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, StringConstraints
from pydantic.alias_generators import to_camel

from .config import MAX_TEXT_CHARACTERS


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class AnalysisItem(ApiModel):
    text: str = Field(min_length=1, max_length=2000)
    source_quote: str | None = Field(default=None, max_length=1000)


class ClarificationItem(ApiModel):
    question: str = Field(min_length=1, max_length=1000)
    reason: str = Field(min_length=1, max_length=1000)


class AnalysisResult(ApiModel):
    summary: str = Field(min_length=1, max_length=5000)
    features: list[AnalysisItem] = Field(max_length=100)
    users: list[AnalysisItem] = Field(max_length=50)
    gaps: list[AnalysisItem] = Field(max_length=50)
    risks: list[AnalysisItem] = Field(max_length=50)
    questions: list[ClarificationItem] = Field(max_length=30)


class StoryDraft(ApiModel):
    title: str = Field(min_length=1, max_length=200)
    as_a: str = Field(min_length=1, max_length=300)
    i_want: str = Field(min_length=1, max_length=1000)
    so_that: str = Field(min_length=1, max_length=1000)
    acceptance_criteria: list[str] = Field(min_length=1, max_length=20)
    priority: Literal["LOW", "MEDIUM", "HIGH", "URGENT"]
    job_role: str | None = Field(default=None, max_length=60)
    source_quotes: list[str] = Field(default_factory=list, max_length=10)


class StoriesResult(ApiModel):
    stories: list[StoryDraft] = Field(max_length=100)


class ClarificationAnswer(ApiModel):
    question: str = Field(min_length=1, max_length=1000)
    answer: str = Field(min_length=1, max_length=5000)


class AnalyzeRequest(ApiModel):
    text: str = Field(min_length=1, max_length=MAX_TEXT_CHARACTERS)
    answers: list[ClarificationAnswer] = Field(default_factory=list, max_length=30)


class StoriesRequest(ApiModel):
    text: str = Field(min_length=1, max_length=MAX_TEXT_CHARACTERS)
    analysis: AnalysisResult
    answers: list[ClarificationAnswer] = Field(default_factory=list, max_length=30)
    active_job_roles: list[str] = Field(default_factory=list, max_length=100)
    rejection_comments: list[str] = Field(default_factory=list, max_length=100)
    kept_story_titles: list[str] = Field(default_factory=list, max_length=200)


EmbedText = Annotated[str, StringConstraints(min_length=1, max_length=2000)]


class EmbedRequest(ApiModel):
    texts: list[EmbedText] = Field(min_length=1, max_length=64)


class KnowledgePassage(ApiModel):
    id: int = Field(ge=1, le=8)
    text: str = Field(min_length=1, max_length=4000)


class AnswerRequest(ApiModel):
    question: str = Field(min_length=1, max_length=500)
    passages: list[KnowledgePassage] = Field(min_length=1, max_length=8)


class KnowledgeAnswer(ApiModel):
    answer: str = Field(min_length=1, max_length=4000)
    citations: list[int] = Field(default_factory=list, max_length=8)
    found: bool


class Usage(ApiModel):
    provider: str
    model: str
    prompt_version: str
    input_tokens: int
    output_tokens: int


# Architecture steps. Names follow PostgreSQL identifiers, so they are safe to show and later to generate from.
_IDENTIFIER = re.compile(r"^[a-z][a-z0-9_]{0,62}$")


def _identifier(value: str) -> str:
    if not _IDENTIFIER.fullmatch(value):
        raise ValueError("must be a lowercase snake_case name")
    return value


def _path(value: str) -> str:
    if not value.startswith("/"):
        raise ValueError("must start with /")
    return value


# Checked after generation rather than as schema patterns, which Gemini's structured output may not support.
Identifier = Annotated[str, Field(min_length=1, max_length=63), AfterValidator(_identifier)]
ColumnType = Literal["uuid", "text", "integer", "bigint", "numeric", "boolean", "date", "timestamptz", "jsonb"]
FieldType = Literal["string", "integer", "number", "boolean", "uuid", "date", "datetime", "object", "array"]
HttpMethod = Literal["GET", "POST", "PUT", "PATCH", "DELETE"]
StoryRef = Annotated[str, Field(min_length=1, max_length=10)]


class ArchitectureComponent(ApiModel):
    name: str = Field(min_length=1, max_length=100)
    responsibility: str = Field(min_length=1, max_length=1000)
    technology: str | None = Field(default=None, max_length=200)


class ArchitectureTechChoice(ApiModel):
    area: str = Field(min_length=1, max_length=100)
    choice: str = Field(min_length=1, max_length=200)
    reason: str = Field(min_length=1, max_length=1000)


class ArchitectureDecision(ApiModel):
    title: str = Field(min_length=1, max_length=200)
    decision: str = Field(min_length=1, max_length=2000)
    reason: str = Field(min_length=1, max_length=2000)
    alternatives: list[Annotated[str, Field(min_length=1, max_length=500)]] = Field(max_length=5)


class ArchitectureRisk(ApiModel):
    text: str = Field(min_length=1, max_length=1000)


class ArchitectureOverview(ApiModel):
    summary: str = Field(min_length=1, max_length=5000)
    components: list[ArchitectureComponent] = Field(max_length=30)
    tech_stack: list[ArchitectureTechChoice] = Field(max_length=30)
    decisions: list[ArchitectureDecision] = Field(max_length=30)
    risks: list[ArchitectureRisk] = Field(max_length=30)


class ArchitectureColumn(ApiModel):
    name: Identifier
    type: ColumnType
    nullable: bool
    description: str = Field(min_length=1, max_length=500)


class ArchitectureRelation(ApiModel):
    column: Identifier
    references_table: Identifier
    references_column: Identifier


class ArchitectureTable(ApiModel):
    name: Identifier
    description: str = Field(min_length=1, max_length=1000)
    columns: list[ArchitectureColumn] = Field(min_length=1, max_length=50)
    relations: list[ArchitectureRelation] = Field(max_length=20)
    story_refs: list[StoryRef] = Field(max_length=50)


class ArchitectureDataModel(ApiModel):
    tables: list[ArchitectureTable] = Field(max_length=60)


class ArchitectureField(ApiModel):
    name: str = Field(min_length=1, max_length=100)
    type: FieldType
    required: bool
    description: str = Field(min_length=1, max_length=500)


class ArchitectureEndpoint(ApiModel):
    method: HttpMethod
    path: Annotated[str, Field(min_length=1, max_length=200), AfterValidator(_path)]
    summary: str = Field(min_length=1, max_length=500)
    request_fields: list[ArchitectureField] = Field(max_length=40)
    response_fields: list[ArchitectureField] = Field(max_length=40)
    roles: list[Annotated[str, Field(min_length=1, max_length=60)]] = Field(max_length=10)
    story_refs: list[StoryRef] = Field(max_length=50)


class ArchitectureApiDesign(ApiModel):
    endpoints: list[ArchitectureEndpoint] = Field(max_length=150)


class ArchitectureStoryInput(ApiModel):
    key: StoryRef
    title: str = Field(min_length=1, max_length=200)
    as_a: str = Field(min_length=1, max_length=300)
    i_want: str = Field(min_length=1, max_length=1000)
    so_that: str = Field(min_length=1, max_length=1000)
    acceptance_criteria: list[Annotated[str, Field(min_length=1, max_length=1000)]] = Field(
        min_length=1, max_length=20
    )
    priority: Literal["LOW", "MEDIUM", "HIGH", "URGENT"]


class ArchitectureStartingPoint(ApiModel):
    status: Literal["APPROVED", "REJECTED"]
    # True when Spring sent only an outline because the full version would not fit.
    compacted: bool = False
    # Stored versions carry story IDs rather than step references, so this context is not schema-checked.
    overview: dict[str, Any]
    data_model: dict[str, Any]
    api_design: dict[str, Any]
    rejection_comments: list[Annotated[str, Field(min_length=1, max_length=2000)]] = Field(
        default_factory=list, max_length=100
    )


class ArchitectureOverviewRequest(ApiModel):
    stories: list[ArchitectureStoryInput] = Field(min_length=1, max_length=200)
    starting_point: ArchitectureStartingPoint | None = None


class ArchitectureDataModelRequest(ArchitectureOverviewRequest):
    overview: ArchitectureOverview


class ArchitectureApiDesignRequest(ArchitectureDataModelRequest):
    data_model: ArchitectureDataModel
