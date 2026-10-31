# Prompt Engineering for AI Code Generation — Evidence-Based Field Guide (2024–2026)

> Research compiled from primary sources (Anthropic, OpenAI, GitHub Copilot docs,
> Aider docs, SWE-bench paper, Reflexion/Self-Refine papers).

## TL;DR — the 5 highest-leverage prompt patterns

1. **Structure every prompt as `Role → Context → Task → Constraints → Acceptance criteria → Output format`, with XML tags.** Both Anthropic's prompt-eng guidance and OpenAI's "write clear instructions / split complex tasks" strategy converge on explicit structure; Cursor/Aider repo rules embody it as `.cursorrules` / `CONVENCTIONS.md`.

2. **Provide curated, minimal context — not "everything."** Put the most important material at the **beginning and end** of the prompt. Performance collapses for information buried in the middle of long contexts (the "Lost in the Middle" effect, Liu et al. 2023, arXiv:2307.03172). Aider explicitly warns: *"Too much irrelevant code will distract and confuse the LLM."*

3. **Decompose: ask for a plan first, approve it, then code — and ask the model to self-critique before finalizing.** Reflexion reaches 91% pass@1 on HumanEval vs GPT-4's 80% via verbal self-reflection; Self-Refine adds ~20% absolute across tasks via iterative feedback. GitHub Copilot docs explicitly recommend "breaking your request down into multiple smaller prompts."

4. **Specify *behavior* and *acceptance criteria* (tests/complexity bounds), not the implementation algorithm** — unless you have a strong reason. Over-constraining the algorithm conflicts with the model's priors and raises hallucination of contradictory detail.

5. **Make performance an explicit constraint** ("N+1-safe," "O(n) target," "no sync I/O in async paths," "minimize DB round-trips") and ask for a complexity analysis + profiling hooks in the response. AI defaults to the most *common* pattern, which is frequently not the most *performant* one.

---

## 1. Prompt engineering for code generation

### Optimal structure

```
<role> ... </role>
<context> relevant files / repo map / prior decisions </context>
<task> one concrete objective </task>
<constraints> language, framework, style, perf budget </constraints>
<acceptance_criteria> tests the code must pass </acceptance_criteria>
<output_format> e.g., "diff against the files above, then a ≤5-line rationale" </output_format>
```

Placing the **task and acceptance criteria at the end** (i.e., *after* the context) is consistent with the "Lost in the Middle" finding that models attend best to the beginning and end of the prompt (Liu et al. 2023, arXiv:2307.03172).

### Personas: evidence *for*

GitHub Copilot's official best-practices doc explicitly endorses personas: *"Completing a task as a specific persona. For example, you can tell Copilot Chat that it is a Senior C++ Developer who cares greatly about code quality, readability, and efficiency, then ask it to review your code."* Persona is most useful for **review/critique** tasks and for biasing toward a *quality dimension* (security, perf, readability) rather than for raw generation.

### Few-shot vs zero-shot

Zero-shot is competitive on **well-represented tasks** (boilerplate CRUD, standard library usage) for frontier models. Few-shot wins when (a) the target is **stylistically specific** (your repo's patterns), (b) the task is **under-represented** in training (domain DSLs, internal APIs), or (c) the output format is non-obvious.

### Chain-of-thought for code

Mixed. For code, free-form "think step by step" helps on **multi-step algorithmic** problems and is roughly neutral on **trivial boilerplate**. The higher-value variant for code is **structured decomposition** ("first enumerate the affected modules, then the data flow, then write the code") rather than stream-of-consciousness CoT.

### Context budget: how much is too much?

There is **no cliff at a fixed token count**; the failure mode is *positional* and *relevance-weighted*. Liu et al. 2023 found accuracy is highest when the answer-relevant context sits at the **start or end** and **degrades when it's in the middle** — even for models marketed as long-context. Practical guidance: cap *actively relevant* context to the fewest files that satisfy the task; if you must include a large context, **restate the key instruction at the end** so it sits in the high-attention zone.

### Acceptance criteria / tests

Provide **executable tests** as the criterion (SWE-bench shows real issues overwhelmingly require coordinating across functions/files — Jimenez et al. 2023, arXiv:2310.06770). Ask the model to *run or trace* the tests before declaring done.

**✅ DO**
```
<role> Senior backend engineer; prefer correctness and minimal allocations. </role>
<context> See UserRepo.ts (in chat). PostgreSQL via Prisma. </context>
<task> Add an endpoint returning users active in the last 7 days, paginated. </task>
<constraints> TypeScript; reuse existing paginate() util; no raw SQL; </constraints>
<acceptance_criteria> Passes the 3 tests in user.controller.spec.ts; O(offset?) avoided — use cursor pagination. </acceptance_criteria>
<output_format> Unified diff + a 3-line rationale citing the complexity class. </output_format>
```

**❌ DON'T**
```
Write a function to get active users. Make it good and efficient. Use Prisma.
// vague: no criteria, no style, no format, "good/efficient" undefined
```

---

## 2. Context management for real codebases

### How the tools manage context

- **Aider** builds a **repo map** (a tree-sitter skeleton of symbols across the whole git repo) so the model "knows" the rest of the codebase *without* dumping it.
- **Cursor** uses `@`-symbols (`@file`, `@codebase`, `@docs`) plus `.cursorrules` / project rules and codebase indexing (embeddings).
- **GitHub Copilot Workspace / Chat** lets you pin repositories, files, and symbols as context and recommends pruning stale threads.
- **Claude Code** and **Continue** lean on agentic tool calls (grep/glob/read) to *fetch* context on demand rather than preloading it.

### Tell it about patterns: file vs summary vs explore

- **Concrete file > summary.** Summaries drop the *style* signal the model needs to match conventions.
- **Let the agent explore for breadth, you curate for correctness.** For a focused change, manually add the 1–3 files that will be edited; let the agent explore for *adjacent* symbols.

### Context rot — the evidence

- "Lost in the Middle" (Liu et al. 2023, arXiv:2307.03172): retrieval accuracy is **U-shaped** w.r.t. position — best at the start/end, worst in the middle, *even for long-context models*.
- Token-heavy contexts also raise **latency and cost** and, with weaker models, **hallucination** of plausible-but-wrong APIs.

**✅ DO**
```
I'm editing payment-service.ts. Here is the file (added to chat) and our repo map.
Rules: all money is integer cents; never float.
Find the N+1 in listCharges() and fix it; don't touch the webhook handler.
```

**❌ DON'T**
```
Here are all 60 files in src/. Please figure out how payments work and optimize them.
```

---

## 3. Performance-focused prompting

AI defaults to the **most common** pattern in its training distribution — which for code is often the *naive* one (loop over rows → N+1; re-render on every prop change; synchronous I/O inside async). You must **name the anti-pattern** to pre-empt it.

Concrete phrasings that work:
- *"N+1-safe: batch all DB access; use `select_related`/`includes`/Prisma `include`."*
- *"Target O(n) worst-case; identify any O(n²) hot path and justify it or eliminate it."*
- *"No blocking I/O inside `async` functions; use the async driver variant."*
- *"Minimize DB round-trips; one query per request where possible."*
- *"For React: memoize expensive children; do not trigger re-renders on unrelated prop changes."*
- *"Include a complexity analysis (time + space) for every public function."*
- *"Add a profiling hook around the hot path so I can measure."*
- *"Identify the hot path before writing code — which loop runs per row under load?"*

Common AI anti-patterns to pre-empt: **N+1 queries** (ORM loops), **unnecessary React re-renders** (missing memo/keys), **sync I/O in async contexts**, **O(n²) string concatenation**, **eager loading everything**, **per-item exception swallowing**, **in-memory dedup of large sets instead of SQL `DISTINCT`**.

**✅ DO**
```
Refactor getDashboard() to be N+1-safe and ≤3 DB queries total.
Before coding: list the current DB calls and the hot path.
After coding: give time/space complexity per function and add a timing log around the main loop.
```

**❌ DON'T**
```
Optimize getDashboard.  // no measurable target, no named anti-pattern, no verification
```

---

## 4. Architecture & quality prompts

### Testability / decoupling
Name the architecture explicitly: *"hexagonal / ports-and-adapters: domain logic has no framework imports; side-effects go behind interfaces injected as constructor params."*

### Reuse existing utilities
State it as a **hard constraint with a discovery step**: *"Before writing any helper, search the repo for an existing util with `ripgrep`. Reuse over reinvent."*

### Error handling / logging / audit
Make these **non-optional**: *"Every external call must have: typed error return, structured log on failure (with correlation id), no bare `catch {}`. Audit-log state transitions."*

### Style guides
Best enforced **out-of-band** as a persistent rule file: Aider's `CONVENTIONS.md`, Cursor's `.cursorrules`, Copilot custom instructions.

**✅ DO**
```
<constraints>
- Hexagonal: domain/ has zero framework imports.
- Inject the time source and DB as interfaces (no new Date(), no global client).
- Reuse lib/paginate.ts; ripgrep before inventing helpers.
- Structured errors: return Result<T, AppError>; log on failure with reqId.
- Match eslint config in repo; do not add new deps.
</constraints>
```

**❌ DON'T**
```
Make it clean, well-architected, and follow best practices.  // undefined terms; model fills with its training average
```

---

## 5. Iterative prompting — conversation patterns that work

### Plan-then-execute
*"Produce a numbered plan with affected files and risks; stop. (I approve / amend.) Then implement step 1 only."* This separates the **reasoning** pass from the **generation** pass and lets you cheaply correct course. **This is the single highest-leverage habit.**

### Two-alternatives
*"Give me 2 design options with trade-offs (perf vs simplicity), then implement the one I pick."*

### Self-review before submit
*"Before finalizing, list 3 ways this could fail (edge cases, perf, security), then revise."* This is **Reflexion** (91% vs 80% pass@1 on HumanEval) and **Self-Refine** (~20% absolute average gain). The model is a meaningfully better *critic* than *generator*; exploit the asymmetry.

### Refactor-after
*"Now refactor for [single dimension]: extract the 3rd arg into a value object."* One dimension per pass beats "make it better at everything."

### When to split
Split when the task touches **>2 files**, has **>1 unclear decision**, or has **mixed concerns** (perf + security + API design).

**✅ DO (turn 1)**
```
Plan only — no code. For "export invoices to PDF": list affected files,
the data flow, 2 failure modes, and an estimated complexity per step. Stop and wait.
```
**✅ DO (turn 2, after approval)**
```
Approved. Implement step 1 (repository query) only. Then self-review for N+1 and
exceptions before showing me the diff.
```
**❌ DON'T**
```
Build the whole invoice export feature with PDFs, email, retries, and tests, all at once.
```

---

## 6. Anti-patterns — prompts that produce bad code

- **Vague optimization**: *"make it better / faster / elegant."* "Elegant" rewards *looks-correct* over *is-correct*. Replace with measurable criteria.
- **Over-constrained**: specifying implementation details that **conflict** ("use a hash map" + "preserve insertion order" + "O(1) lookup by two keys") → model fabricates a fake solution.
- **Under-specified acceptance**: no tests, no complexity target → you can't tell if it succeeded; model can't self-verify.
- **Kitchen-sink**: many concerns in one turn → shallow treatment of each. Split.
- **Looks-correct rewards**: asking for "clean/idiomatic/elegant" without correctness gates → polished wrong code.
- **Stale context**: keeping a long dead conversation degrades output. Start a new conversation when context changes.
- **Trusting the first token**: Reflexion/Self-Refine show first outputs are reliably worse than refined ones; never ship turn-1 code for non-trivial tasks.
- **Dumping the whole repo**: invites "lost in the middle" + latency + hallucinated APIs.

**✅ DO** — replace `"optimize this"` with `"reduce getFeed() to ≤3 queries and O(n) work; prove with the attached benchmark; fail loud if exceeded."`
**❌ DON'T** — `"optimize this and make it elegant and production-ready."`

---

## Final: Prompt template for HYDRA-style fullstack tasks

A ready-to-adapt skeleton. Place repo rules in a persistent file (`.cursorrules` / `CONVENTIONS.md`) so this stays task-focused.

```text
<role>{{ROLE}}  <!-- e.g., "Senior fullstack engineer; bias to correctness, then perf, then brevity." --></role>

<context>
Repository map / conventions are loaded as project rules (do not re-derive style).
Files in scope (added to chat): {{FILES}}   <!-- add ONLY the files to be edited -->
Relevant prior decisions: {{DECISIONS}}      <!-- link to ADRs / docs, not full text -->
External APIs / schemas referenced: {{SCHEMAS}}
</context>

<task>
{{TASK}}   <!-- ONE concrete objective -->
</task>

<constraints>
- Language/framework: {{STACK}}
- Reuse existing utils: ripgrep before inventing; do not add deps without asking.
- Architecture: {{ARCH}}  <!-- e.g., hexagonal; domain logic free of framework imports -->
- Performance: {{PERF_BUDGET}}  <!-- e.g., "≤3 DB queries; O(n) target; no sync I/O in async" -->
- Security/errors: typed errors, structured logs with reqId, no bare catch.
- Do NOT touch: {{OFF_LIMITS}}
</constraints>

<acceptance_criteria>
Must pass: {{TESTS_OR_SPECS}}   <!-- executable tests preferred -->
Complexity: state time/space per public function; flag any O(n²).
Self-check: before finalizing, list 3 failure modes and fix them (Reflexion-style).
</acceptance_criteria>

<output_format>
1. A 5-line plan (files + risks) — STOP and wait for my approval.   <!-- plan-then-execute -->
2. After approval: unified diff against the files above.
3. A ≤6-line rationale: complexity class, queries issued, trade-offs taken.
4. The exact tests you ran/traced to verify.
</output_format>
```

**Usage notes:**
1. Keep `<task>` and `<acceptance_criteria>` near the **end** — that is the high-attention zone per Lost-in-the-Middle.
2. Add only the 1–3 files to be edited; let the repo map handle the rest.
3. Always run the plan-then-execute split; never accept turn-1 output for non-trivial work.
4. Re-iterate with one dimension per refinement pass.

---

### Source index (verified during research)
- Liu et al. 2023, "Lost in the Middle: How Language Models Use Long Contexts" — arXiv:2307.03172
- Jimenez et al. 2023, "SWE-bench" — arXiv:2310.06770
- Shinn et al. 2023, "Reflexion" — arXiv:2303.11366
- Madaan et al. 2023, "Self-Refine" — arXiv:2303.17651
- Wang et al. 2022, "Self-Consistency" — arXiv:2203.11171
- Wei et al. 2022, "Chain-of-Thought Prompting" — arXiv:2201.11903
- Aider docs — "Tips" and "Specifying coding conventions" (aider.chat)
- GitHub Copilot docs — "Best practices for using GitHub Copilot" (docs.github.com)
- Anthropic prompt-engineering guidance & OpenAI prompt-engineering guide
