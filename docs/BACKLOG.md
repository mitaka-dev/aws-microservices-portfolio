# Backlog

## Bugs

- **user-service: duplicate email returns 500 instead of 409**
  `POST /users` with an already-registered email hits a DB unique constraint and bubbles up as an unhandled 500. Should catch `DataIntegrityViolationException` (or the underlying `PSQLException`) and return 409 Conflict.
