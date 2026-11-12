# Changelog

All notable changes to this module are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - Unreleased

### Added
- Alternative text of images. The mixin `jmix:jahiaAlt` extends `jmix:image` with the i18n
  property `jahia_alt`, and the GraphQL mutation `generateImageAltText(path, language,
  includeSubNodes, overwrite)` fills it with a vision model, for one image or for every image below
  a path. It runs under the caller's session, saves each image on its own, skips an image that
  already has a text unless `overwrite` is set, and reports one result per image.
- Configuration keys `alt.tier`, `alt.max.length`, `alt.max.images`, `alt.max.image.bytes`,
  `alt.max.tokens` and `alt.prompt`.
- Configuration key `llm.tier`, the genai-connector tier of the tags (default `lite`).
- Unit tests (JUnit 5, Mockito).

### Changed
- **Breaking**: the module calls its models through genai-connector, which is now a required
  dependency. The provider, the model and the API key are configured in genai-connector. The keys
  `llm.provider` and `anthropic.*`, `openai.*`, `deepseek.*` are no longer read.
- The tagging action answers HTTP 503 when genai-connector has no model for the tier, and HTTP 429
  when the user reached its rate limit.

### Removed
- **Breaking**: the built-in Anthropic, OpenAI and DeepSeek providers and the exported
  `org.jahia.se.modules.contenttags.service.spi` package (`LlmProvider`, `LlmSettings`).

## [1.0.1] - 2026-10-07

### Added
- Dialog control to choose the **number of tags** to generate (default 5).
- Dialog **"Replace existing tags"** checkbox: when checked the generated tags replace the
  current tag list (previous behaviour); when unchecked they are merged into it (deduplicated).
- Configuration key `llm.tag.count.default` (default 5) for the fallback tag count.
- Configuration key `llm.tag.count.max` (default 20, hard-capped at 50) — the enforced ceiling
  on how many tags a request may generate; the requested count is clamped to it server-side.
- Read-only `contentTagsConfig` action so the dialog reads the configured default and maximum
  on open; the "number of tags" input and its label now reflect the configured maximum.

### Changed
- The tag **count and language are now injected authoritatively** by the module on every
  request, so `llm.user.prompt` is style guidance only and must no longer hardcode a number.
  The shipped default prompt was updated accordingly.
- `ContentTagsService.generateTags` now takes a `numberOfTags` argument; the action accepts an
  optional `numberOfTags` request parameter.

## [1.0.0] - 2026-07-10

Initial release. Successor of the `anthropic-tags` module, rebuilt LLM-agnostic
with clean history.

### Added
- Content Editor "Auto Tagging" action (3-dots menu) that generates semantic tags
  for the selected content and fills `jmix:tagged` / `j:tagList`.
- LLM-agnostic provider layer: `LlmProvider` OSGi SPI with Anthropic (Messages API),
  OpenAI and DeepSeek (Chat Completions) implementations, selected via the
  `llm.provider` configuration key. Providers are called over the JDK HTTP client
  with no vendor SDK jars embedded.
- Configurable model, base URL, max tokens, temperature, source-text cap and prompt
  per provider via `org.jahia.se.modules.contenttags.cfg`.
- Action visibility gated with `useNodeChecks` (shown on `jnt:content` / `jnt:page` /
  `jmix:mainResource` and only when the module is installed on the site).
- English and French translations.

### Security
- POST-only render action requiring an authenticated user with `jcr:write_default`;
  node content is read through the caller's JCR session (no system-session escalation).
- CSRF guard whitelist scoped to `*.generateContentTagsAction.do`.
- No API key shipped in the bundle; content text truncated and logged at DEBUG only.
- `jsoup` upgraded to 1.18.3. Security review and scanner output under `findings/`.
