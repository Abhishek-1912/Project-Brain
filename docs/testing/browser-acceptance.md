# Browser Acceptance Test

Use this checklist to confirm Phase 2 (requirements and stories), Phase 3 (architecture), Phase 4 (knowledge), and Phase 5 (MCP access) work end to end in a real browser and in Claude Code. It uses dummy data only.

## Before you start

- Supabase has pgvector enabled (`backend/db/bootstrap/enable_vector.sql`) and migrations through `V7` applied.
- The root `.env` has the AI settings. Never commit it or paste its values anywhere.
  - Groq: `AI_PROVIDER=groq` and `GROQ_API_KEY`.
  - Gemini: `AI_PROVIDER=gemini` (or unset) and `GEMINI_API_KEY`.
  - Both need `AI_SERVICE_TOKEN`.
- Three services are running:
  - Backend on port 8080, started with the `local` profile.
  - AI service on `127.0.0.1:8090`, started with only its own settings and `FASTEMBED_CACHE_PATH=$HOME/.cache/fastembed`.
  - Frontend on [http://localhost:5173](http://localhost:5173).
- You are signed in as an Owner of a test organization with an active project.
- You have the fake document `docs/test-data/dummy-prd.md`.
- For Part 4, Claude Code is installed on this computer.

If a step shows a rate-limit message, wait a minute and click **Try again**. Groq's free tier allows about 8,000 tokens per minute.

## Part 1: Requirements and stories (Phase 2)

| Step | Do this | Expect |
|---|---|---|
| 1 | Open the project and click **Requirements**. | The yellow "Use dummy data only" banner. |
| 2 | Click **Upload document**, choose `dummy-prd.md`, tick the dummy-data box, and upload. | The document page opens. Upload stays disabled until the box is ticked. |
| 3 | Click **Analyze document**. | Progress, then a summary, features, users, gaps, and risks. Quotes show **Source verified** or **Source not found in document**. Usage shows the model and "Free tier". |
| 4 | Answer one clarification question and mark another **Mark not needed**. | The answer is saved, and the other question shows **Not needed**. |
| 5 | Click **Update analysis**. | The analysis becomes Version 2. |
| 6 | Click **Generate stories**. | Story drafts appear. All are **Draft**, even though the document says "approve all stories". |
| 7 | Open one story and reject it with a comment. | The story shows **Rejected**, and the comment is in its review history. |
| 8 | Open another story, make sure it has a job role, and click **Approve story**. | The story shows **Approved**. |
| 9 | Click **Create 1 task**, then go back to the project's tasks. | One new task in **Backlog**. The story shows **Task created**. |
| 10 | Look for **Create tasks** again. | It is not offered, and no duplicate task appears. |

## Part 2: Architecture (Phase 3)

Part 1 must leave at least one story **Approved** or **Task created** in this project.

| Step | Do this | Expect |
|---|---|---|
| 11 | Click **Architecture**, then **Generate architecture**. | "Step 1 of 3", then 2, then 3. Then a draft and its usage with "Free tier". |
| 12 | Open the draft. | Overview, tables with columns, and endpoints. Each table and endpoint lists its stories or shows **Not linked to a story**. |
| 13 | Click **Edit data model**, change a table description, and click **Save data model**. | The new description shows on the page. |
| 14 | Reject the draft with a comment, such as "Rename the bookings table". | The version shows **Rejected**, and the comment is in review history. |
| 15 | Go back and click **Update architecture**. | A new draft that responds to the comment. The old draft stays in history. |
| 16 | Approve the new draft. | It becomes the current architecture. Any earlier approved version shows **Superseded**. |
| 17 | Click **Download Markdown** and open the file. | The overview, tables, endpoints, story names, and review history. |

## Part 3: Knowledge (Phase 4)

Parts 1 and 2 should leave this project with an uploaded document, answered questions, approved stories, and an approved architecture.

| Step | Do this | Expect |
|---|---|---|
| 18 | Go back to the project's tasks and click **Knowledge**. | The yellow banner, an **Ask this project** box with a disabled **Ask** button, and **Indexed sources**. |
| 19 | Click **Rebuild index**. | "Queued N items for indexing." Sources show **Waiting** or **Indexing**, then **Ready** within a minute, without reloading. |
| 20 | Ask each question in `docs/test-data/knowledge-questions.md` and fill in its Result table. | Questions 1 to 8 answer correctly with numbered sources that link to the document, a story, or the architecture. Questions 9 and 10 say "Not found in this project's knowledge." |
| 21 | Click **New note**, add a note with a made-up fact, such as "Pelicans nest near the harbour", and save it. | The note appears under **Notes**, and a **Note** source becomes **Ready**. |
| 22 | Ask about the note's fact. | The answer cites the note, and the link scrolls to it. |
| 23 | Edit the note to a different fact, then ask about the old fact. | The edit is saved. The old fact is no longer found once the source is **Ready** again. |
| 24 | Delete the note and confirm. | The note is gone, and asking about its fact says not found. |
| 25 | Sign in as a Member or Viewer of the same organization and open **Knowledge**. | You can ask questions and read notes, but **Rebuild index**, **New note**, **Edit**, and **Delete** are hidden. |

## Part 4: Access tokens and MCP (Phase 5)

Parts 1 to 3 should leave this project with knowledge, an approved architecture, and an active job role in the organization.

| Step | Do this | Expect |
|---|---|---|
| 26 | Click **Access tokens** in the organization menu. | The page explains what tokens are for, with **Create token**. |
| 27 | Create a token named "Claude Code", choose **Read and write** and 7 days, then click **Create token**. | The secret is shown once, with a `claude mcp add` command that contains it. After **Done**, the secret is gone and the list shows the token as **Active**. |
| 28 | Copy the command and run it in a terminal, then run `claude mcp list`. | `project-brain` is listed as connected. |
| 29 | In Claude Code, ask: "Use project-brain to list my projects." | It names this organization's projects and no others. |
| 30 | Ask it a question from `docs/test-data/knowledge-questions.md` using project-brain. | A short answer with cited sources, the same as the Knowledge tab gives. |
| 31 | Ask it to show this project's current architecture. | The approved architecture as Markdown. |
| 32 | Ask it to create a Backlog task for this project, using one of the job roles from `list_job_roles`. | The task appears in the project's Backlog in the app, created by you. |
| 33 | Create a **Read only** token, run `claude mcp remove project-brain`, add the new token with its command, and ask it to create another task. | Claude Code reports that the token is read-only, and no task is created. |
| 34 | In the app, revoke the token Claude Code is using, then ask Claude Code to list projects again. | The call fails because the token is no longer valid. The list shows **Revoked**. |

## Part 5: Records

| Step | Do this | Expect |
|---|---|---|
| 35 | Open the **Audit log**. | Entries for the upload, AI runs, story decisions, task conversion, architecture edit, rejection, and approval, knowledge rebuild, questions asked, note changes, token creation and revocation, and the MCP task marked as coming from MCP. No document, story, note, question, answer, or token text appears in them. |

## If something goes wrong

- **"Approve at least one user story before generating the architecture":** approve a story in this project's **Requirements**, then try again.
- **Approve button missing:** another person may hold the required job role, or self-approval may be off in the organization settings.
- **Rate limit or timeout:** wait a minute, then click **Try again**. Finished architecture steps are reused.
- **AI service unavailable:** check that the AI service is running on port 8090 and that `AI_SERVICE_TOKEN` matches for both services.
- **Claude Code can't connect:** check that the backend is running on port 8080, the token is active and not expired, and the command uses the `/mcp` address.
- **A knowledge source shows Failed:** read its message, check the AI service, then click **Rebuild index**.
- **Unrelated passages cited, or not found for a question the documents answer:** note the question. The relevance threshold may need tuning.

Write down the step number and what you saw on screen when reporting a problem.
