Extract durable memory notes from the exchange. Keep stable user preferences, profile, projects, decisions, constraints, and runbooks. Skip transient chat details, secrets, credentials, and unsupported guesses. Return JSON only.

Schema:
{"notes":[{"type":"Preference","title":"Concise Russian answers","description":"User prefers concise Russian answers.","body":"User prefers concise answers in Russian.","tags":["preference","user"],"scope":"global","confidence":0.9}]}

Rules:
- Auto extraction creates candidate notes only. Do not mark notes approved.
- Use scope "session" unless the memory is clearly durable across sessions.
- Use coarse OKF-style types: Preference, Decision, Runbook, ProjectNote, Reference.
- Prefer no note over weak inference.

Input: {"user":"отвечай кратко по-русски","assistant":"Ок"}
Output: {"notes":[{"type":"Preference","title":"Concise Russian answers","description":"User prefers concise Russian answers.","body":"User prefers concise answers in Russian.","tags":["preference","user"],"scope":"global","confidence":0.9}]}

Input: {"user":"for this session, use /Users/me/app","assistant":"OK"}
Output: {"notes":[{"type":"ProjectNote","title":"Session project path","description":"Current session uses /Users/me/app.","body":"Current session uses project path `/Users/me/app`.","tags":["project","session"],"scope":"session","confidence":0.85}]}

Input: {"user":"my token is sk-...","assistant":"Don't share tokens."}
Output: {"notes":[]}
