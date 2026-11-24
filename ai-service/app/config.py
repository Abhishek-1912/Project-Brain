import os

MAX_FILE_BYTES = 10 * 1024 * 1024
MAX_TEXT_CHARACTERS = 200_000
MAX_DOCX_EXPANDED_BYTES = 50 * 1024 * 1024
MAX_DOCX_ENTRIES = 5_000
# Multipart boundaries and headers add a small amount to the file size.
MAX_REQUEST_BYTES = MAX_FILE_BYTES + 1024 * 1024


def service_token() -> str:
    return os.environ.get("AI_SERVICE_TOKEN", "")
