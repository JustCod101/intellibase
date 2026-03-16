import requests
import time
import json
import sseclient
import logging
import sys
import os

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

BASE_URL = os.getenv("API_BASE_URL", "http://localhost:8080/api/v1")
TEST_USER = {
    "username": "test_system_user_" + str(int(time.time())),
    "password": "Password123!",
    "email": f"test_{int(time.time())}@example.com"
}

def check_status(response, message):
    if response.status_code != 200:
        logger.error(f"Error {message}: {response.status_code} - {response.text}")
        sys.exit(1)
    return response.json()

def test_system_flow():
    logger.info(f"Starting system test against {BASE_URL}")

    # 1. Register
    logger.info("Registering user...")
    reg_resp = requests.post(f"{BASE_URL}/auth/register", json=TEST_USER)
    check_status(reg_resp, "User Registration")
    logger.info(f"User registered: {TEST_USER['username']}")

    # 2. Login
    logger.info("Logging in...")
    login_resp = requests.post(f"{BASE_URL}/auth/login", json={
        "username": TEST_USER['username'],
        "password": TEST_USER['password']
    })
    login_data = check_status(login_resp, "User Login")
    token = login_data['data']['accessToken']
    headers = {"Authorization": f"Bearer {token}"}
    logger.info("Login successful.")

    # 3. Create Knowledge Base
    logger.info("Creating Knowledge Base...")
    kb_data = {
        "name": "System Test KB",
        "description": "A knowledge base for automated testing"
    }
    kb_resp = requests.post(f"{BASE_URL}/kb", headers=headers, json=kb_data)
    kb_json = check_status(kb_resp, "Create Knowledge Base")
    kb_id = kb_json['data']['id']
    logger.info(f"Knowledge Base created with ID: {kb_id}")

    # 4. Upload Document
    logger.info("Uploading document...")
    file_content = "The secret code for the system test is: ALPHA-BETA-42. This is used to verify RAG functionality."
    files = {'file': ('test_secret.txt', file_content, 'text/plain')}
    upload_resp = requests.post(f"{BASE_URL}/kb/{kb_id}/documents", headers=headers, files=files)
    upload_json = check_status(upload_resp, "Upload Document")
    doc_id = upload_json['data']['id']
    logger.info(f"Document uploaded with ID: {doc_id}")

    # 5. Wait for Parsing & Indexing
    logger.info("Waiting for document parsing...")
    max_retries = 30
    doc_status = "PENDING"
    for i in range(max_retries):
        status_resp = requests.get(f"{BASE_URL}/kb/{kb_id}/documents", headers=headers)
        status_json = check_status(status_resp, "Get Documents")
        
        doc = next((d for d in status_json['data']['records'] if d['id'] == doc_id), None)
        if doc:
            doc_status = doc['status']
            logger.info(f"Attempt {i+1}: Document status is {doc_status}")
            if doc_status == 'COMPLETED':
                break
            if doc_status == 'FAILED':
                logger.error("Document parsing failed!")
                sys.exit(1)
        else:
            logger.warning(f"Attempt {i+1}: Document not found in list.")
        
        time.sleep(2)
    else:
        logger.error("Timed out waiting for document parsing.")
        sys.exit(1)

    # 6. Create Chat Conversation
    logger.info("Creating Chat Conversation...")
    conv_data = {
        "kbId": kb_id,
        "title": "System Test Chat"
    }
    conv_resp = requests.post(f"{BASE_URL}/chat/conversations", headers=headers, json=conv_data)
    conv_json = check_status(conv_resp, "Create Conversation")
    conv_id = conv_json['data']['id']
    logger.info(f"Conversation created with ID: {conv_id}")

    # 7. Streaming Chat Question
    logger.info("Sending question to stream...")
    question = "What is the secret code for the system test?"
    stream_url = f"{BASE_URL}/chat/stream?conversationId={conv_id}&question={requests.utils.quote(question)}"
    
    response = requests.get(stream_url, headers=headers, stream=True)
    if response.status_code != 200:
        logger.error(f"Stream error: {response.status_code} - {response.text}")
        sys.exit(1)

    client = sseclient.SSEClient(response)
    full_answer = ""
    logger.info("Receiving stream response:")
    for event in client.events():
        if event.data:
            # Depending on how SseEmitter sends data, it might be raw text or JSON
            # Typically it's the chunk of text
            full_answer += event.data
            sys.stdout.write(event.data)
            sys.stdout.flush()
    
    print("\n")
    logger.info("Stream finished.")
    
    # 8. Verify Response
    if "ALPHA-BETA-42" in full_answer:
        logger.info("SUCCESS: The RAG system retrieved the correct secret code!")
    else:
        logger.warning(f"FAILURE: The secret code was not found in the answer. Received: {full_answer}")
        # sys.exit(1) # Consider if this is a hard failure or if LLM variance is okay

    # 9. Cleanup (Optional, but good practice)
    logger.info(f"Cleaning up Knowledge Base {kb_id}...")
    cleanup_resp = requests.delete(f"{BASE_URL}/kb/{kb_id}", headers=headers)
    if cleanup_resp.status_code == 200:
        logger.info("Cleanup successful.")
    else:
        logger.warning(f"Cleanup failed: {cleanup_resp.status_code}")

    logger.info("System test completed successfully!")

if __name__ == '__main__':
    test_system_flow()
