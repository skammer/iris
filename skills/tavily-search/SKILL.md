---
name: tavily-search
description: Search the web with Tavily and extract clean Markdown from specific URLs using skill-owned scripts.
---

# Tavily Search and Extract

Use `web_search` for current web research and `web_extract` to read a known page
as clean Markdown. They route providers automatically. Do not fetch ordinary
HTML with raw `http` when extraction is available.

The scripts below are operator fallbacks for direct Tavily access. Normal agent
work should use the first-class tools.

## Search

```bash
python3 ~/.config/iris/skills/tavily-search/scripts/search.py \
  "LadybugDB practical examples" --max-results 5
```

Options:

- `--max-results 1..20` — result count; default `5`.
- `--depth basic|advanced` — search depth; default `basic`.
- `--topic general|news|finance` — default `general`.
- `--include-images` — include image results.
- `--include-answer` — include Tavily's synthesized answer.
- `--raw-content markdown|text` — include cleaned page content in search results.

Search returns compact JSON with `title`, `url`, `content`, and `score`.

## Extract URL to Markdown

```bash
python3 ~/.config/iris/skills/tavily-search/scripts/extract.py \
  --url "https://docs.ladybugdb.com/get-started/"
```

For long pages, request only relevant chunks:

```bash
python3 ~/.config/iris/skills/tavily-search/scripts/extract.py \
  --url "https://docs.ladybugdb.com/get-started/" \
  --query "Python usage examples" --chunks-per-source 3
```

Options:

- Repeat `--url` for up to 20 URLs.
- `--depth basic|advanced` — use `advanced` for JS-heavy or difficult pages.
- `--format markdown|text` — default `markdown`.
- `--query` — rerank extracted chunks for the research question.
- `--chunks-per-source 1..5` — requires `--query`; default `3` when query is set.
- `--max-chars` — cap each result after extraction; default `7000`, `0` means full.
- `--timeout 1..60` — Tavily extraction timeout.

The normalized response contains `content`, `chars`, `returned_chars`, and
`truncated`. A repeated call with identical inputs returns the same content; use
the result, change the query/URL, or finish the answer.

## Credentials

Scripts read `TAVILY_API_KEY`, then `TAVILY_API_KEY_FILE`, then
`~/.config/iris/secrets/tavily-api-key`. Never print or pass the key as an
argument.

## Fallback

If Tavily fails, use `searcharvester-fallback`. Its `/extract` endpoint also
returns Markdown and supports bounded sizes. Use raw `http` only for JSON APIs,
RSS, raw source files, or explicit text endpoints such as `llms.txt`.
