# Role-Based Agents and Human Approval

Status: Direction approved by the developer on 2026-11-22. Detailed design and plan are written before Phase 1.5 starts.
Applies to: Phase 1.5 onward

## 1. Intent

Project Brain works like a real software company. Role-based AI agents do the work, and real people with matching skills verify and approve it, because one person cannot judge every technology.

The founder can start alone and hold every role. When employees join, they are assigned to roles without changing the system.

## 2. Roles

| Agent | Produces | Human approver |
|---|---|---|
| Project Manager Agent | Requirements, stories, tasks, timeline | Project Manager |
| Architect Agent | Architecture, database and API design | Architect or Tech Lead |
| Backend Agent | Backend code and tests | Backend Developer |
| Frontend Agent | Frontend code and tests | Frontend Developer |
| QA Agent | Test runs, defects, quality reports | QA Engineer |
| DevOps Agent | Deployment changes and release preparation | DevOps Engineer |

The role list should be configurable per organization.

## 3. Two kinds of role

- **Access role:** Owner, Admin, Member, Viewer. Controls what a user can access in an organization. Built in Phase 1.
- **Job role:** Project Manager, Architect, Backend, Frontend, QA, DevOps. Controls whose work a person can approve on a project. Added in Phase 1.5.

A person can hold several job roles on a project.

## 4. Work flow

```text
Task assigned to a job role
  -> Agent works
  -> Automated checks and QA Agent
       failed -> back to the agent
  -> Waiting for human approval
       rejected with comment -> agent fixes and resubmits
       approved -> next stage or merge
```

Example: the PM Agent writes the requirement and the PM approves it. The Architect Agent designs the API and the Architect approves it. The Backend Agent opens a pull request, the QA Agent tests it, and the Backend Developer reviews and approves the merge.

## 5. Rules

1. Every task has one responsible job role.
2. Only a person holding that job role on the project can approve the task.
3. If nobody holds the role, approval goes to the organization Owner, and the record marks it as an Owner fallback.
4. An agent can never approve work, including its own. AI review is advice only.
5. A rejection requires a comment, which the agent receives as input.
6. Production deployment, database changes and secret access also require Owner or Tech Lead approval.
7. Every submission, approval and rejection records who, when, what version and why.
8. Each agent run records its model, tokens, cost and task.
9. Each agent gets only the project context, tools and credentials its role needs.

## 6. Roadmap change

Add **Phase 1.5: Team and Approvals** after Phase 1, because Phase 2 outputs already need human approval.

Phase 1.5 scope:

- Employee invitations.
- Project team and job roles.
- A responsible job role on each task.
- Approval records and the approve or reject flow.
- A "waiting for my review" queue.

Agent execution itself stays in Phase 6. Phase 1 scope does not change, and these additions are expected to extend its schema through new Flyway migrations.
