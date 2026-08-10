# Evidence Boundary

Generated evidence belongs in a run-specific directory named with a non-secret `runId`.

Allowed evidence:

- sanitized AgentTeams task and decision summaries;
- manifest digest and validator result;
- MCP tool names, timestamps, statuses, and bounded error codes;
- Nubase deployment ID and step statuses;
- test reports with credentials and user data removed;
- explicit human approval or rejection.

Never store raw HTTP headers, prompts containing private data, API keys, JWTs, cookies, database dumps, Function secret values, `.env` files, or `.nubase/config.json`. Build any submission artifact from Git-tracked files, not from the working directory.
