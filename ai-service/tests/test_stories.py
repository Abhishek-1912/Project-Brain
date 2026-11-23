import json

from app.schemas import StoriesResult

ANALYSIS = {
    "summary": "A dummy booking app.",
    "features": [],
    "users": [],
    "gaps": [],
    "risks": [],
    "questions": [],
}


def story(**overrides):
    return {
        "title": "Book a table",
        "asA": "diner",
        "iWant": "to book a table",
        "soThat": "I have a seat",
        "acceptanceCriteria": ["A booking is saved"],
        "priority": "HIGH",
        "jobRole": "Backend",
        "sourceQuotes": ["Users can book a table"],
        **overrides,
    }


def generate(client, headers, **overrides):
    body = {
        "text": "Users can book a table",
        "analysis": ANALYSIS,
        "answers": [],
        "activeJobRoles": ["Backend", "QA"],
        "rejectionComments": [],
        "keptStoryTitles": [],
        **overrides,
    }
    return client.post("/v1/stories", json=body, headers=headers)


def test_generates_stories_with_usage(client, auth_headers, use_provider):
    provider = use_provider({"stories": [story()]})

    response = generate(client, auth_headers)

    assert response.status_code == 200
    assert response.json()["result"]["stories"] == [story()]
    assert response.json()["usage"]["promptVersion"] == "stories-v1"
    assert provider.calls[0]["schema"] is StoriesResult


def test_story_role_outside_active_list_becomes_none(client, auth_headers, use_provider):
    use_provider({"stories": [story(jobRole="Security")]})

    response = generate(client, auth_headers)

    assert response.json()["result"]["stories"][0]["jobRole"] is None


def test_story_role_matches_active_list_ignoring_case(client, auth_headers, use_provider):
    use_provider({"stories": [story(jobRole=" backend ")]})

    response = generate(client, auth_headers)

    assert response.json()["result"]["stories"][0]["jobRole"] == "Backend"


def test_regeneration_context_is_sent_as_data(client, auth_headers, use_provider):
    provider = use_provider({"stories": [story()]})

    generate(
        client,
        auth_headers,
        rejectionComments=["Add cancellation rules"],
        keptStoryTitles=["Approved login story"],
    )

    payload = json.loads(provider.calls[0]["document"])
    assert payload["rejectionComments"] == ["Add cancellation rules"]
    assert payload["keptStoryTitles"] == ["Approved login story"]
    assert payload["activeJobRoles"] == ["Backend", "QA"]
    assert "Add cancellation rules" not in provider.calls[0]["system"]


def test_invalid_priority_is_retried(client, auth_headers, use_provider):
    provider = use_provider({"stories": [story(priority="CRITICAL")]}, {"stories": [story()]})

    response = generate(client, auth_headers)

    assert response.status_code == 200
    assert len(provider.calls) == 2


def test_story_without_acceptance_criteria_is_invalid(client, auth_headers, use_provider):
    use_provider({"stories": [story(acceptanceCriteria=[])]}, {"stories": [story(acceptanceCriteria=[])]})

    response = generate(client, auth_headers)

    assert response.status_code == 502
    assert response.json()["code"] == "INVALID_OUTPUT"
    assert set(response.json()) == {"code", "usage"}
