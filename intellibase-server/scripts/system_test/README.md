# Intellibase Server System Test

This directory contains an end-to-end system test script for the Intellibase Server.

## Prerequisites

- Python 3.8+
- The Intellibase Server must be running (default: `http://localhost:8080`)
- Dependencies: `requests`, `sseclient-py`

## Installation

```bash
pip install -r requirements.txt
```

## Running the Test

```bash
python system_test.py
```

### Configuration

You can override the base URL by setting the `API_BASE_URL` environment variable:

```bash
export API_BASE_URL=http://your-server-address:8080/api/v1
python system_test.py
```

## What it tests

1. **User Registration**: Creates a new user with a unique timestamp-based username.
2. **User Login**: Authenticates and retrieves a JWT token.
3. **Knowledge Base Management**: Creates a new Knowledge Base (KB).
4. **Document Ingestion**: Uploads a text document containing a "secret code".
5. **Asynchronous Processing**: Polls the document status until it's `COMPLETED`.
6. **RAG Chat**: 
   - Creates a conversation linked to the KB.
   - Sends a question regarding the "secret code".
   - Streams the response using SSE.
   - Verifies the answer contains the expected code.
7. **Cleanup**: Deletes the created Knowledge Base.
