from fastapi import FastAPI, File, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .config import MAX_FILE_BYTES
from .extract import ExtractionError, extract_text
from .security import ServiceGuard

# This is a private service, so it publishes no API documentation.
app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
app.add_middleware(ServiceGuard)


@app.exception_handler(ExtractionError)
async def extraction_error(_request: Request, error: ExtractionError) -> JSONResponse:
    return JSONResponse(status_code=error.status, content={"code": error.code})


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
