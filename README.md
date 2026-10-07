# Automatic Content Tags - Jahia Content Editor UI Extension

This module is a Jahia UI extension for the Content Editor. It generates semantic tags for content using a configurable LLM provider: **Anthropic**, **OpenAI** or **DeepSeek**.

Compatible with Jahia 8.2. Requires Java 17 to build.

## Supported providers

| `llm.provider` | API | Default model | Auth |
|---|---|---|---|
| `anthropic` | Messages API (`/v1/messages`) | `claude-sonnet-4-6` | `x-api-key` |
| `openai` | Chat Completions (`/v1/chat/completions`) | `gpt-5-mini` | Bearer token |
| `deepseek` | Chat Completions (`/v1/chat/completions`) | `deepseek-chat` | Bearer token |

## Features

- **Content Editor integration**: adds an "Auto Tagging" action to the Content Editor 3-dots menu.
- **LLM-agnostic**: one OSGi configuration key switches between Anthropic (Messages API), OpenAI and DeepSeek (Chat Completions API). New providers can be added by registering an `LlmProvider` OSGi service.
- **AI-powered tagging**: extracts the internationalized text of the selected JCR node (through the calling user's session) and asks the model for relevant tags in the language chosen by the editor.
- **Editor controls**: choose the **number of tags** (1-20, default 5) and whether to **replace** the existing tags or **add to** them, directly in the dialog.
- **Automatic tag field update**: fills `jmix:tagged` / `j:tagList` with the generated tags in the open editor form.
- **No embedded SDKs**: providers are called over plain HTTPS with the JDK HTTP client - no vendor SDK jars in the bundle.

## Architecture

```
src/main/java/org/jahia/se/modules/contenttags/
├── actions/GenerateContentTagsAction.java   # POST-only render action (CSRF-whitelisted)
├── service/ContentTagsService.java          # Public service interface
├── service/internal/                        # Text extraction, config, response parsing
├── service/spi/LlmProvider.java             # Provider SPI (name + complete(prompt, settings))
└── provider/                                # AnthropicProvider, OpenAiProvider, DeepSeekProvider

src/javascript/                              # React 18 UI extension (Module Federation)
```

## Installation

1. Build the module:
   ```bash
   mvn clean install
   ```
2. Deploy the generated `target/automatic-content-tags-<version>.jar` to your Jahia instance (module management UI or `digital-factory-data/modules`).

## Configuration

Create `org.jahia.se.modules.contenttags.cfg` in `digital-factory-data/karaf/etc/`:

```properties
# Active provider: anthropic | openai | deepseek
llm.provider=anthropic

# API key of the selected provider (required)
anthropic.api.key=sk-ant-...
#openai.api.key=sk-...
#deepseek.api.key=sk-...

# Optional overrides (defaults shown)
#anthropic.model=claude-sonnet-4-6
#openai.model=gpt-5-mini
#deepseek.model=deepseek-chat
#llm.max.tokens=1024
#llm.max.source.chars=6000

# Default number of tags when the dialog does not specify one (1-20).
#llm.tag.count.default=5

# Style guidance only. The number of tags (from the dialog) and the tag language are
# injected by the module, so do NOT hardcode a number or a language in this prompt.
#llm.user.prompt=Respond ONLY with a JSON array of concise single- or two-word strings, without markdown, explanations or duplicates.
```

**Never commit an API key to source control.** The `.cfg` shipped inside the bundle contains empty keys on purpose.

> The number of tags and the tag language come from the dialog on every request and are injected
> authoritatively by the module. Keep `llm.user.prompt` to style guidance only — a hardcoded count
> or language there will conflict with the request.

## Usage

1. Open a content item in the Content Editor.
2. Open the 3-dots menu and click **Auto Tagging**.
3. Select the tag language, set how many tags you want (default 5), and choose whether to
   **replace existing tags** (checked) or **add to them** (unchecked).
4. Click **Apply**. The tag field is filled with the generated tags; save to persist them.

> Note: tags are generated from the **saved** content of the node. Unsaved edits in the open
> editor form are not analysed - save first, then generate tags.

## Security notes

- The render action accepts **POST only**, requires an authenticated user with `jcr:write_default` permission on the node, and reads content strictly through the caller's JCR session (no system session).
- The CSRF guard whitelist covers only `*.generateContentTagsAction.do`.
- Content text sent to the provider is truncated to `llm.max.source.chars` and logged at DEBUG level only.

## Development

- Frontend: React 18 (see `src/javascript/AutoTags/`), built with Webpack/Module Federation.
- Backend: Java 17 OSGi Declarative Services (see `src/main/java/org/jahia/se/modules/contenttags/`).

Build with Java 17 (`JAVA_HOME` must point at a JDK 17). The `frontend-maven-plugin`
runs the Webpack build during `mvn install`.

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## License

Licensed under the [MIT License](LICENSE).
