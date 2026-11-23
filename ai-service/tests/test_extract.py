import io
import zipfile

import pytest
from docx import Document
from reportlab.pdfgen import canvas

TEN_MB = 10 * 1024 * 1024


def pdf_bytes(text: str | None) -> bytes:
    output = io.BytesIO()
    pdf = canvas.Canvas(output)
    if text:
        pdf.drawString(72, 720, text)
    pdf.showPage()
    pdf.save()
    return output.getvalue()


def docx_bytes() -> bytes:
    document = Document()
    document.add_paragraph("Users can reset passwords.")
    table = document.add_table(rows=1, cols=1)
    table.cell(0, 0).text = "Admins can view reports."
    output = io.BytesIO()
    document.save(output)
    return output.getvalue()


def extract(client, headers, name, content, media_type="application/octet-stream"):
    return client.post("/v1/extract", files={"file": (name, content, media_type)}, headers=headers)


@pytest.mark.parametrize(
    ("name", "content", "expected"),
    [
        ("prd.pdf", pdf_bytes("Dummy login requirement"), ["Dummy login requirement"]),
        ("prd.docx", docx_bytes(), ["Users can reset passwords.", "Admins can view reports."]),
        ("prd.md", "# Goals\nDummy café app".encode(), ["# Goals", "Dummy café app"]),
        ("prd.txt", b"\xef\xbb\xbfPlain dummy text", ["Plain dummy text"]),
    ],
)
def test_extracts_pdf_docx_markdown_and_text(client, auth_headers, name, content, expected):
    response = extract(client, auth_headers, name, content)

    assert response.status_code == 200
    body = response.json()
    for text in expected:
        assert text in body["text"]
    assert not body["text"].startswith("﻿")
    assert body["characters"] == len(body["text"])


@pytest.mark.parametrize(
    ("name", "content"),
    [
        ("prd.exe", b"MZ program"),
        ("prd", b"no extension"),
        ("prd.pdf", b"plain text pretending to be a PDF"),
        ("prd.docx", b"plain text pretending to be Word"),
        ("prd.txt", b"binary\x00content"),
        ("prd.md", b"\xff\xfe\x00invalid"),
    ],
)
def test_rejects_unsupported_type_and_mismatched_content(client, auth_headers, name, content):
    response = extract(client, auth_headers, name, content)

    assert response.status_code == 415
    assert response.json() == {"code": "UNSUPPORTED_TYPE"}


def test_accepts_file_at_10_mb_boundary_then_checks_text(client, auth_headers):
    response = extract(client, auth_headers, "prd.txt", b" " * TEN_MB)

    assert response.status_code == 422
    assert response.json() == {"code": "NO_TEXT"}


def test_rejects_file_over_10_mb(client, auth_headers):
    response = extract(client, auth_headers, "prd.txt", b"a" * (TEN_MB + 1))

    assert response.status_code == 413
    assert response.json() == {"code": "FILE_TOO_LARGE"}


def test_rejects_scanned_pdf_with_no_text(client, auth_headers):
    response = extract(client, auth_headers, "scan.pdf", pdf_bytes(None))

    assert response.status_code == 422
    assert response.json() == {"code": "NO_TEXT"}


def test_accepts_exactly_200000_characters(client, auth_headers):
    response = extract(client, auth_headers, "prd.txt", b"a" * 200_000)

    assert response.status_code == 200
    assert response.json()["characters"] == 200_000


def test_rejects_text_over_200000_characters(client, auth_headers):
    response = extract(client, auth_headers, "prd.txt", b"a" * 200_001)

    assert response.status_code == 422
    assert response.json() == {"code": "TEXT_TOO_LONG"}


@pytest.mark.parametrize(
    ("name", "content"),
    [
        ("broken.pdf", b"%PDF-1.7\nthis is not a real document"),
        ("broken.docx", b"PK\x03\x04broken zip"),
    ],
)
def test_rejects_corrupt_pdf_and_docx(client, auth_headers, name, content):
    response = extract(client, auth_headers, name, content)

    assert response.status_code == 422
    assert response.json() == {"code": "CORRUPT_FILE"}


def test_rejects_zip_without_word_document(client, auth_headers):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr("notes.txt", "not a Word file")

    response = extract(client, auth_headers, "fake.docx", output.getvalue())

    assert response.status_code == 422
    assert response.json() == {"code": "CORRUPT_FILE"}


def test_rejects_docx_that_expands_beyond_safe_limit(client, auth_headers):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("word/document.xml", b"a" * (51 * 1024 * 1024))

    response = extract(client, auth_headers, "bomb.docx", output.getvalue())

    assert response.status_code == 422
    assert response.json() == {"code": "CORRUPT_FILE"}
