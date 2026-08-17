Extract durable memory changes from the provided exchange or session transcript. Keep stable user preferences, profile, projects, decisions, constraints, and runbooks. Skip transient chat details, secrets, credentials, and unsupported guesses. Return JSON only.

Schema:
{"notes":[{"operation":"create","target_id":null,"expected_revision":null,"type":"Preference","title":"Concise answers","description":"User prefers concise answers.","body":"User prefers concise answers.","tags":["preference","user"],"scope":"global","confidence":0.9,"evidence":"Message 1: user explicitly requested concise answers."}]}

Rules:
- Use operation "update" when existing_notes contains the same durable subject and the transcript corrects or materially extends it. Copy its id into target_id and revision into expected_revision.
- Use operation "create" only when no existing note represents the durable subject.
- Omit unchanged duplicates. Never update a merely related note.
- Creates and updates are candidates only. Do not mark memory approved.
- Use scope "session" unless the memory is clearly durable across sessions.
- Use coarse OKF-style types: Preference, Decision, Runbook, ProjectNote, Reference.
- Prefer no note over weak inference.
- Keep evidence under 1000 characters. Cite only decisive message/event ids or a compact paraphrase; never copy the full transcript.
- Explicit session transcripts may contain many messages; consolidate repeated or final facts instead of saving turn-by-turn summaries.

Input: {"user":"answer concisely","assistant":"OK","existing_notes":[]}
Output: {"notes":[{"operation":"create","target_id":null,"expected_revision":null,"type":"Preference","title":"Concise answers","description":"User prefers concise answers.","body":"User prefers concise answers.","tags":["preference","user"],"scope":"global","confidence":0.9,"evidence":"User explicitly requested concise answers."}]}

Input: {"user":"for this session, use /Users/me/app","assistant":"OK","existing_notes":[]}
Output: {"notes":[{"operation":"create","target_id":null,"expected_revision":null,"type":"ProjectNote","title":"Session project path","description":"Current session uses /Users/me/app.","body":"Current session uses project path `/Users/me/app`.","tags":["project","session"],"scope":"session","confidence":0.85,"evidence":"User explicitly selected /Users/me/app for this session."}]}

Input: {"user":"my token is sk-...","assistant":"Don't share tokens.","existing_notes":[]}
Output: {"notes":[]}
