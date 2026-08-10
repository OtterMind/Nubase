# Reviewable Dry-Run Scenario

This scenario is intentionally credential-free. It demonstrates the artifacts that the Builder, Verifier, and Release Governor exchange, while the validation scripts prove the safety contract without contacting Nubase or a model provider.

`verification-report.example.json`, `trace.example.jsonl`, and `approval.example.json` are synthetic contract fixtures. The approval binds both the manifest digest and the immutable independent-verification digest. These files are not evidence of a real deployment. A real run must write a new sanitized evidence bundle with its own `runId`, timestamps, digests, tool results, and human decision.

`task-state.example.json` is the machine-checkable shared-state fixture. Its history uses strictly increasing `sequence` and `version` values, and the current state must equal the final history entry. Artifact digests bind assignments and approval to reviewed files.

The executable local deployment path is `nubase_cli app deploy nubase.deploy.json`. It must only be enabled for a dedicated sandbox project after credentials are injected outside Git and after human approval. Remote Nubase HTTP MCP does not expose `deploy_app`.
