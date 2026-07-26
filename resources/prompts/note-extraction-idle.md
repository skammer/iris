Extract only high-impact durable memory notes from an idle chat window. Most
windows should produce no notes. Return JSON only.

Schema:
{"notes":[{"operation":"create","target_id":null,"expected_revision":null,"type":"Preference","title":"Concise Russian answers","description":"User prefers concise Russian answers.","body":"User prefers concise answers in Russian.","tags":["preference","user"],"scope":"global","confidence":0.9}]}

Save only:
- Stable user preferences, constraints, project decisions, reusable runbooks.
- Durable project facts that will improve future work quality.
- Corrections to prior assumptions that should affect future behavior.
- Environment/tool facts that are likely reusable across sessions.

Skip:
- Greetings, acknowledgements, status chatter, routine progress updates.
- One-off requests already completed.
- Transient debugging details unless the final reusable cause/fix is clear.
- Raw logs, command output, credentials, API keys, tokens, private secrets.
- Weak guesses, inferred preferences, or facts with confidence below 0.85.

Rules:
- Use operation "update" when existing_notes contains the same durable subject and this window corrects or materially extends it. Copy its id into target_id and revision into expected_revision.
- Use operation "create" only when no existing note represents the subject.
- Omit unchanged duplicates. Never update a merely related note.
- Creates and updates are candidates only. Do not mark memory approved.
- Use scope "session" unless clearly durable across sessions.
- Use OKF-style types: Preference, Decision, Runbook, ProjectNote, Reference.
- Prefer {"notes":[]} over noisy notes.
- Consolidate repeated/final conclusions; do not summarize the conversation.

Input:
{"user":"[1] user: привет\n\n[2] assistant: привет","assistant":"Idle memory extraction.","existing_notes":[]}
Output:
{"notes":[]}

Input:
{"user":"[1] user: for this repo always run focused clj-kondo only, full lint is noisy\n\nEvents:\n[7] tool-execution-end status=success tool=bb","assistant":"Idle memory extraction.","existing_notes":[]}
Output:
{"notes":[{"operation":"create","target_id":null,"expected_revision":null,"type":"Runbook","title":"Use focused clj-kondo in this repo","description":"For this repo, focused clj-kondo is preferred because full lint is noisy.","body":"For this repo, run focused `clj-kondo` on touched files; full lint is noisy and should not be treated as task-specific signal.","tags":["runbook","clojure","lint"],"scope":"project","confidence":0.9}]}

Input:
{"user":"[1] user: my HA token is abc123","assistant":"Idle memory extraction.","existing_notes":[]}
Output:
{"notes":[]}
