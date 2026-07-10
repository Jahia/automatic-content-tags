# Changelog

All notable changes to this module are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
