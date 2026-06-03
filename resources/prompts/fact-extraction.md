Extract durable memory facts from the exchange. Keep stable user preferences, profile, projects, decisions, constraints. Skip transient chat details, secrets, credentials, and unsupported guesses. Return JSON only.

Output format:
{"facts":[{"subject":"user","predicate":"prefers","object":"concise answers","scope":"global","confidence":0.9}]}

Examples:

Input: {"user":"I prefer terse answers in Russian.","assistant":"Понял."}
Output: {"facts":[{"subject":"user","predicate":"prefers","object":"terse answers in Russian","scope":"global","confidence":0.9}]}

Input: {"user":"For this session, use project /Users/me/app.","assistant":"Using that project."}
Output: {"facts":[{"subject":"current session","predicate":"uses project","object":"/Users/me/app","scope":"session","confidence":0.85}]}

Input: {"user":"My API key is sk-secret. Also, what time is it?","assistant":"I cannot store secrets. It is 10:00."}
Output: {"facts":[]}
