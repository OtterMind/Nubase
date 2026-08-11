# Evidence Boundary

Generated evidence belongs in a run-specific directory named with a non-secret `runId`.

Allowed evidence:

- sanitized AgentTeams task and decision summaries;
- manifest digest and validator result;
- MCP tool names, timestamps, statuses, and bounded error codes;
- Nubase deployment ID, step statuses, and the non-secret ownership version needed to prove exact-version rollback;
- service-role verification that reserved metadata is present before rollback and absent afterward, without marker content or a public URL;
- test reports with credentials and user data removed;
- explicit human approval or rejection.

Never store raw HTTP headers, prompts containing private data, API keys, JWTs, cookies, database dumps, Function secret values, `.env` files, `.nubase/config.json`, marker content, object-store control-plane responses, or a marker public URL. Build any submission artifact from Git-tracked files, not from the working directory.
