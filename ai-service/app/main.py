from typing import Any

from fastapi import Depends, FastAPI, File, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .config import MAX_FILE_BYTES, MAX_TEXT_CHARACTERS
from .extract import ExtractionError, extract_text
from .providers import get_provider
from .generation import analyze as run_analysis
from .embeddings import DIMENSIONS, MODEL_NAME, Embedder, EmbeddingUnavailable, embed_texts, get_embedder
from .generation import answer_question, generate_stories, run_architecture_step
from .provider import AiProvider, ProviderError
from .prompts import architecture_payload
from .schemas import (
    AnalyzeRequest,
    AnswerRequest,
    ArchitectureApiDesignRequest,
    ArchitectureDataModelRequest,
    ArchitectureOverviewRequest,
    EmbedRequest,
    StoriesRequest,
)
from .security import ServiceGuard

# This is a private service, so it publishes no API documentation.
app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
app.add_middleware(ServiceGuard)


@app.exception_handler(ExtractionError)
async def extraction_error(_request: Request, error: ExtractionError) -> JSONResponse:
    return JSONResponse(status_code=error.status, content={"code": error.code})


@app.exception_handler(ProviderError)
async def provider_error(_request: Request, error: ProviderError) -> JSONResponse:
    content: dict[str, Any] = {"code": error.code}
    if error.usage:
        content["usage"] = error.usage.model_dump(by_alias=True)
    return JSONResponse(status_code=error.status, content=content)


@app.exception_handler(EmbeddingUnavailable)
async def embedding_unavailable(_request: Request, _error: EmbeddingUnavailable) -> JSONResponse:
    return JSONResponse(status_code=503, content={"code": "EMBEDDING_UNAVAILABLE"})


@app.exception_handler(RequestValidationError)
async def invalid_request(_request: Request, _error: RequestValidationError) -> JSONResponse:
    # FastAPI's default response can echo submitted document text.
    return JSONResponse(status_code=422, content={"code": "INVALID_REQUEST"})


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/extract")
def extract(file: UploadFile = File(...)) -> dict[str, str | int]:
    content = file.file.read(MAX_FILE_BYTES + 1)
    text = extract_text(file.filename or "", content)
    return {"text": text, "characters": len(text)}


@app.post("/v1/analyze")
def analyze(
    request: AnalyzeRequest, provider: AiProvider = Depends(get_provider)
) -> dict[str, Any]:
    result, usage = run_analysis(provider, request)
    return {"result": result.model_dump(by_alias=True), "usage": usage.model_dump(by_alias=True)}


@app.post("/v1/stories")
def stories(
    request: StoriesRequest, provider: AiProvider = Depends(get_provider)
) -> dict[str, Any]:
    result, usage = generate_stories(provider, request)
    return {"result": result.model_dump(by_alias=True), "usage": usage.model_dump(by_alias=True)}


@app.post("/v1/embed")
def embed(request: EmbedRequest, embedder: Embedder = Depends(get_embedder)) -> dict[str, Any]:
    return {"vectors": embed_texts(embedder, request.texts), "model": MODEL_NAME, "dimensions": DIMENSIONS}


@app.post("/v1/answer")
def answer(request: AnswerRequest, provider: AiProvider = Depends(get_provider)) -> dict[str, Any]:
    result, usage = answer_question(provider, request)
    return {"result": result.model_dump(by_alias=True), "usage": usage.model_dump(by_alias=True)}


def _architecture(step: str, request, provider: AiProvider) -> dict[str, Any]:
    # Measured on the exact payload Gemini would receive, before any provider call.
    if len(architecture_payload(request)) > MAX_TEXT_CHARACTERS:
        raise ExtractionError("TEXT_TOO_LONG", 422)
    result, usage = run_architecture_step(provider, step, request)
    return {"result": result.model_dump(by_alias=True), "usage": usage.model_dump(by_alias=True)}


@app.post("/v1/architecture/overview")
def architecture_overview(
    request: ArchitectureOverviewRequest, provider: AiProvider = Depends(get_provider)
) -> dict[str, Any]:
    return _architecture("overview", request, provider)


@app.post("/v1/architecture/data-model")
def architecture_data_model(
    request: ArchitectureDataModelRequest, provider: AiProvider = Depends(get_provider)
) -> dict[str, Any]:
    return _architecture("data-model", request, provider)


@app.post("/v1/architecture/api-design")
def architecture_api_design(
    request: ArchitectureApiDesignRequest, provider: AiProvider = Depends(get_provider)
) -> dict[str, Any]:
    return _architecture("api-design", request, provider)
