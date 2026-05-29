---
description: Execute a phase from docs/plan/BUILD-PLAN.md
argument-hint: <phase-number, or omit for next unchecked>
---
This command should be executed in plan mode. If you're not in plan mode, switch first.

You're starting work on a build phase from `docs/plan/BUILD-PLAN.md`.

Phase: $ARGUMENTS

The project status (loaded via SessionStart hook) tells you the next planned phase and last completed phase. If $ARGUMENTS is empty, use the next planned phase.

Workflow:

1. Read the phase from `docs/plan/BUILD-PLAN.md`: goal, tasks, exit criteria, and any dependencies. If the phase checkbox is already `- [x]`, stop immediately — tell the user it's done and show the next unchecked phase instead.
2. Briefly restate the goal in your own words and list any CLAUDE.md workarounds or skills that apply (e.g., SB4 workarounds, `postgresql-flyway-migrations`, `terraform-module-conventions`).
3. Propose the implementation approach in a few bullets. For any OpenTofu phase, always show `tofu plan` output and wait for approval before applying.
4. Wait for confirmation, then implement.
5. Verify the exit criteria. Run automatable ones (`./mvnw verify`, `tofu validate`, curl health checks). For manual ones (AWS console checks, screenshots), ask the user to confirm.
6. When all criteria pass:
   - Flip `- [ ]` to `- [x]` for this phase in `BUILD-PLAN.md`.
   - Update `docs/plan/STATUS.md`: move this phase into Completed Phases, update Current Phase to the next unchecked one.

If $ARGUMENTS doesn't match a phase number in `BUILD-PLAN.md`, stop and ask the user to clarify.
