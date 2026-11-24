from typing import Literal

from pydantic import BaseModel, ConfigDict, Field
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


class Usage(ApiModel):
    provider: str
    model: str
    prompt_version: str
    input_tokens: int
    output_tokens: int
