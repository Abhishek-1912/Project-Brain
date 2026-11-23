import io
import logging
import zipfile
from pathlib import PurePath

from docx import Document
from docx.table import Table
from pypdf import PdfReader

from .config import (
    MAX_DOCX_ENTRIES,
    MAX_DOCX_EXPANDED_BYTES,
    MAX_FILE_BYTES,
    MAX_TEXT_CHARACTERS,
)

# Parser warnings can quote document content.
logging.getLogger("pypdf").setLevel(logging.CRITICAL)


class ExtractionError(Exception):
    def __init__(self, code: str, status: int) -> None:
        super().__init__(code)
        self.code = code
        self.status = status


def _unsupported() -> ExtractionError:
    return ExtractionError("UNSUPPORTED_TYPE", 415)


def _corrupt() -> ExtractionError:
    return ExtractionError("CORRUPT_FILE", 422)


def _too_long() -> ExtractionError:
    return ExtractionError("TEXT_TOO_LONG", 422)


def extract_text(filename: str, content: bytes) -> str:
    if len(content) > MAX_FILE_BYTES:
        raise ExtractionError("FILE_TOO_LARGE", 413)

    suffix = PurePath(filename).suffix.lower()
    if suffix == ".pdf":
        text = _extract_pdf(content)
    elif suffix == ".docx":
        text = _extract_docx(content)
    elif suffix in {".md", ".markdown", ".txt"}:
        text = _extract_plain(content)
    else:
        raise _unsupported()

    text = text.strip()
    if not text:
        raise ExtractionError("NO_TEXT", 422)
    if len(text) > MAX_TEXT_CHARACTERS:
        raise _too_long()
    return text


def _extract_plain(content: bytes) -> str:
    if b"\x00" in content:
        raise _unsupported()
    try:
        return content.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise _unsupported() from error


def _extract_pdf(content: bytes) -> str:
    if not content.startswith(b"%PDF-"):
        raise _unsupported()
    try:
        reader = PdfReader(io.BytesIO(content))
        if reader.is_encrypted:
            raise _corrupt()
        parts: list[str] = []
        total = 0
        for page in reader.pages:
            part = page.extract_text() or ""
            total += len(part)
            # Whitespace can be trimmed later, so allow one extra limit before stopping.
            if total > MAX_TEXT_CHARACTERS * 2:
                raise _too_long()
            parts.append(part)
    except ExtractionError:
        raise
    except Exception as error:
        raise _corrupt() from error
    return "\n".join(parts)


def _extract_docx(content: bytes) -> str:
    if not content.startswith(b"PK"):
        raise _unsupported()
    try:
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            entries = archive.infolist()
            expanded = sum(entry.file_size for entry in entries)
            names = {entry.filename for entry in entries}
        if (
            len(entries) > MAX_DOCX_ENTRIES
            or expanded > MAX_DOCX_EXPANDED_BYTES
            or "word/document.xml" not in names
        ):
            raise _corrupt()
        document = Document(io.BytesIO(content))
        parts: list[str] = []
        _collect_blocks(document.iter_inner_content(), parts)
    except ExtractionError:
        raise
    except Exception as error:
        raise _corrupt() from error
    return "\n".join(parts)


def _collect_blocks(blocks, parts: list[str]) -> None:
    for block in blocks:
        if isinstance(block, Table):
            seen = set()
            for row in block.rows:
                for cell in row.cells:
                    # Merged cells appear once per covered grid position.
                    if cell._tc in seen:
                        continue
                    seen.add(cell._tc)
                    _collect_blocks(cell.iter_inner_content(), parts)
        elif block.text:
            parts.append(block.text)
