# Agent Training and Evaluation

Status: Direction approved by the developer on 2026-11-22. Detailed design and plan are written before Phase 6 starts.
Applies to: Phase 6 onward
Related: `2026-11-22-role-based-agent-workflow-design.md`

## 1. Intent

Agents should produce accurate, reviewable work before Project Brain is used on client projects or launched as SaaS.

We do not train a model from scratch or fine-tune one at the start. Current models already know common technologies. Each agent is onboarded like an experienced new hire, with company rules, project context, tools, checks and feedback.

Agents are not expected to be 100% accurate. Human approval remains required.

## 2. How an agent works

```text
Read task and acceptance criteria
  -> Retrieve relevant project knowledge
  -> Write a short plan
  -> Do the work with allowed tools
  -> Run checks
       failed -> read the error and fix, within a retry and cost limit
  -> Submit evidence for human approval
```

Stop and escalate to a person when requirements conflict, the retry or cost limit is reached, or a sensitive action is needed.

## 3. What each agent receives

| Layer | Purpose | Example |
|---|---|---|
| Role prompt | Responsibility and limits | Backend Agent cannot approve or deploy |
| Playbooks | Repeatable step-by-step methods | How to add a tenant-safe API |
| Project knowledge | Facts for this project | PRD, architecture, API contract, decisions |
| Examples | The standard to follow | Reviewed code and tests |
| Tools | Actions allowed for the role | Git, test runner, read-only database |
| Checks | Objective verification | Tests, lint, security scan, architecture rules |

All prompts and playbooks are version-controlled, and every agent run records the versions it used.

## 4. Knowledge rules

Use short, specific, retrievable documents instead of long course-style notes.

Include:

- Company coding standards.
- Rules specific to this codebase.
- Official documentation for the exact versions in use.
- Good examples.
- Past mistakes and their fixes.

Avoid:

- General tutorials the model already knows.
- Outdated documentation.
- Unreviewed AI-generated notes.
- Client data in shared knowledge. Client knowledge stays scoped to that organization and project.

## 5. Learning from feedback

1. Reviewer rejection comments are stored with the task and agent run.
2. Repeated mistakes are grouped into a proposed lesson.
3. A person with the matching job role approves or rejects the lesson.
4. An approved lesson becomes a new playbook version.
5. The benchmark runs again before that version becomes the default.

Agents never change their own prompts or playbooks without human approval.

## 6. Evaluation before launch

**Stage 1: Benchmark tasks.** Use completed Project Brain tasks with known requirements and tests, starting with Phase 1. Agents work from the starting commit without seeing the final answer.

**Stage 2: Test projects.** Build a business website, an ecommerce application and a SaaS MVP.

**Stage 3: Shadow mode.** On real work, the agent produces a result that is never merged, and reviewers compare it with the human-approved result.

**Stage 4: Gradual trust.** Move from small internal tasks to medium features, then supervised client work.

## 7. Metrics

- First-time human approval rate.
- Test pass rate.
- Number of rework cycles.
- Security and tenant-isolation defects.
- Requirements missed.
- Time per task.
- AI and infrastructure cost per task.

Any change to a model, prompt, playbook or tool runs the benchmark and is compared with the current version.

Starting release gates, to be adjusted using real results:

- Zero known tenant-isolation or critical security defects.
- 100% of required tests pass before human review.
- At least 70% first-time approval on benchmark tasks.
- Average rework of two cycles or fewer.
- Cost per task stays inside the configured budget.

## 8. Fine-tuning

Consider fine-tuning only if evaluation shows a repeated problem that prompts, playbooks, retrieval and checks cannot fix, and the expected gain justifies the cost and maintenance. Client data is never used for it without explicit contractual permission.
