# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Goal

Clojure port of the R [ellmer](https://github.com/tidyverse/ellmer) package: a unified, multi-provider LLM client library supporting chat, tool calling, and structured schema output. Reference implementations exist in sibling projects:
- `/opt/mt/repos/hyperphor/alzabo/src/clj/hyperphor/alzabo/llm.clj` — OpenAI chat completions, JSON/code extraction
- `/opt/mt/repos/pici/pimento/src/clj/pimento/openai.clj` — OpenAI responses API, RAG, streaming, background requests

## Build & Test

This project uses Leiningen (matching sibling project conventions):

```bash
lein repl          # start REPL
lein test          # run all tests
lein test :only hyperphor.ellellem.core-test/my-test   # run single test
```

## Architecture

### Namespace layout

```
src/clj/hyperphor/ellellem/
  core.clj          — public API surface
  providers/
    openai.clj      — OpenAI chat completions + responses API
    anthropic.clj   — Anthropic Messages API
  chat.clj          — conversation/turn management (chat history, multi-turn)
  tools.clj         — tool/function call protocol (define, dispatch, execute)
  tools/            - tool definitions (eg weather)
  schema.clj        — structured output / JSON schema coercion
  util.clj          — shared: JSON extraction, streaming SSE parsing
```

### Provider abstraction

Each provider namespace exposes a `complete` function taking a normalized request map and returning a normalized response map. The `core` ns routes calls based on `:provider` key. Keep provider-specific HTTP details inside the provider namespace.

### HTTP + JSON conventions (from sibling projects)

- HTTP: `hato.client` with `:as :json` for auto-parsing
- JSON: `clojure.data.json` with `:key-fn keyword`
- Env vars for API keys: `environ.core` — `:openai-api-key`, `:anthropic-api-key`
- Template strings: `hyperphor.multitool.core/tx` (used throughout sibling projects)

### Tool calling

Tools are Clojure maps `{:name :description :parameters :fn}`. `tools.clj` handles serialization to provider formats and dispatching responses back to the `:fn`. The protocol should be provider-agnostic so the same tool definitions work with OpenAI and Anthropic.

### Streaming

Use `hato.client` with `:as :stream` for SSE. Parse `data: {...}` lines and deliver via core.async channel or callback — see `pimento.openai/api-post-stream` for the existing pattern.

## Key Dependencies

```clojure
[hato "1.0.0"]                    ; HTTP client
[clojure.data.json "2.5.0"]       ; JSON
[environ "1.2.0"]                 ; API keys from env
[com.hyperphor/multitool "0.2.4"] ; utility lib (u/tx template strings, etc.)
```
