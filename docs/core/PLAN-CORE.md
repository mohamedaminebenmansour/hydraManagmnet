# HYDRA core plan (reference for every core task)

## Goal
Make the system true, secure and flexible before building money modules:
centralised records, nothing deleted, every change traceable, user-level security by scope,
configurable controls, workflow, tickets, notifications, one inbox per role.

## Hard invariants (never configurable)
1. Tenant isolation enforced by PostgreSQL row-level security, not only by Java.
2. Nothing is deleted: business rows are archived (`archived_at`) or reversed.
3. Every change to a business record writes an audit event: actor, time, before, after, reason.
4. Posted money is never edited in place (later modules).

## Soft controls (configurable by the owner, per tenant)
Mode per control: `OFF` · `INFO` (recorded silently) · `WARN` (default: message, user continues,
owner sees it) · `BLOCK` (refused until fixed or overridden with a reason).
Every customer starts with almost everything on `WARN`.

## Layers (dependencies only point down)
- L1 Platform: security/access, audit/history, events, settings & controls, workflow, tickets,
  notifications, files, numbering, grid views. Generic services keyed by `RecordRef(type, id)`.
- L2 Structure: legal entities, groupements, parties, projects & sites, engagements, equipment.
- L3 Documents: one framework for BC, BL, bon de retour, facture, avoir, bon de paiement,
  décharge, bon de caisse, relevé de caisse.
- L4 Money: ledger, pockets, obligations, settlements, floats.
- L5 Management modules, added later one by one, plugging into L3/L4.

## Wave 1 steps (this folder of prompts)
0. Green CI (fix TicketServiceTest).
1a. Tenant context on every DB connection.
1b. Row-level security policies on all tenant tables, for the `hydra_app` role.
2a. `audit_event` with before/after/reason + `AuditRecorder`.
2b. Archive instead of delete.
3. `RecordRef` + one timeline per record.
4. Settings + control policies (OFF/INFO/WARN/BLOCK) + owner screen.
UX. Role home « Mes actions » with daily/weekly/monthly items and alerts.

## Wave 2 (later)
Access scopes (tenant/entity/project/site) · structure (entities, groupements with dated shares,
party roles and aliases, projects + sites, engagements + sites, equipment) · generic workflow
(project optional, subject handlers) · tickets linked to any record + auto-tickets with dedup ·
notifications from domain events → inbox → daily email · document framework · money core.

## Decisions already taken
- Sites: a project has many sites; a site belongs to one project.
- Partner company money is recorded in the operator's books (partner current account + funding envelopes).
- Groupement shares are dated; payment mode is a groupement setting.
- Visibility is configurable by the owner (role × scope).
- Site cash is an imprest: fixed float, bons de caisse, relevé de caisse, replenishment.
