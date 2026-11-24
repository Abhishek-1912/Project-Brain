# Browser Acceptance Test

Use this checklist to confirm Phase 2 (requirements and stories) and Phase 3 (architecture) work end to end in a real browser. It uses dummy data only.

## Before you start

- Supabase has migrations through `V5` applied.
- The root `.env` has the AI settings. Never commit it or paste its values anywhere.
  - Groq: `AI_PROVIDER=groq` and `GROQ_API_KEY`.
  - Gemini: `AI_PROVIDER=gemini` (or unset) and `GEMINI_API_KEY`.
  - Both need `AI_SERVICE_TOKEN`.
- Three services are running:
  - Backend on port 8080, started with the `local` profile.
  - AI service on `127.0.0.1:8090`, started with only its own settings.
  - Frontend on [http://localhost:5173](http://localhost:5173).
- You are signed in as an Owner of a test organization with an active project.
- You have the fake document `docs/test-data/dummy-prd.md`.

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

## Part 3: Records

| Step | Do this | Expect |
|---|---|---|
| 18 | Open the **Audit log**. | Entries for the upload, AI runs, story decisions, task conversion, and architecture edit, rejection, and approval. No document or story text appears in them. |

## If something goes wrong

- **"Approve at least one user story before generating the architecture":** approve a story in this project's **Requirements**, then try again.
- **Approve button missing:** another person may hold the required job role, or self-approval may be off in the organization settings.
- **Rate limit or timeout:** wait a minute, then click **Try again**. Finished architecture steps are reused.
- **AI service unavailable:** check that the AI service is running on port 8090 and that `AI_SERVICE_TOKEN` matches for both services.

Write down the step number and what you saw on screen when reporting a problem.
