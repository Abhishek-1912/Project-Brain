from typing import TypeVar

from pydantic import BaseModel, ValidationError

from .prompts import (
    ANALYSIS_SYSTEM,
    ANALYSIS_VERSION,
    STORIES_SYSTEM,
    STORIES_VERSION,
    analysis_payload,
    stories_payload,
)
from .provider import AiProvider, ProviderError
from .schemas import AnalysisResult, AnalyzeRequest, StoriesRequest, StoriesResult, Usage

T = TypeVar("T", bound=BaseModel)
MAX_ATTEMPTS = 2


def analyze(provider: AiProvider, request: AnalyzeRequest) -> tuple[AnalysisResult, Usage]:
    return _generate(
        provider, ANALYSIS_SYSTEM, analysis_payload(request), AnalysisResult, ANALYSIS_VERSION
    )


def generate_stories(provider: AiProvider, request: StoriesRequest) -> tuple[StoriesResult, Usage]:
    result, usage = _generate(
        provider, STORIES_SYSTEM, stories_payload(request), StoriesResult, STORIES_VERSION
    )
    roles = {role.strip().casefold(): role for role in request.active_job_roles}
    for story in result.stories:
        key = story.job_role.strip().casefold() if story.job_role else ""
        story.job_role = roles.get(key)
    return result, usage


def _generate(
    provider: AiProvider, system: str, document: str, schema: type[T], prompt_version: str
) -> tuple[T, Usage]:
    input_tokens = 0
    output_tokens = 0
    model = getattr(provider, "model", "unknown")

    def usage() -> Usage:
        return Usage(
            provider=provider.name,
            model=model,
            prompt_version=prompt_version,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
        )

    for _attempt in range(MAX_ATTEMPTS):
        try:
            outcome = provider.generate_json(system, document, schema)
        except ProviderError as error:
            input_tokens += error.input_tokens
            output_tokens += error.output_tokens
            if error.code == "INVALID_OUTPUT":
                continue
            error.usage = usage()
            raise
        input_tokens += outcome.input_tokens
        output_tokens += outcome.output_tokens
        model = outcome.model
        try:
            parsed = schema.model_validate(outcome.data)
        except ValidationError:
            continue
        return parsed, usage()
    failure = ProviderError("INVALID_OUTPUT")
    failure.usage = usage()
    raise failure
