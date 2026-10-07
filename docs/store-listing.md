# Jahia Store listing — Automatic Content Tags

Source copy for the store.jahia.com listing. Keep in sync with the module version.

- **Module name:** Automatic Content Tags
- **Module id:** automatic-content-tags
- **Version:** 1.0.0
- **Compatibility:** Jahia 8.2
- **License:** MIT
- **Author:** Jahia Solutions Group
- **Source:** https://github.com/Jahia/automatic-content-tags
- **Suggested category:** Editor tools / Productivity / AI

---

## Short description (one line)

Generate content tags automatically in the Content Editor using the AI provider of your choice — Anthropic, OpenAI or DeepSeek.

## Description

Automatic Content Tags adds an **Auto Tagging** action to the Jahia Content Editor. In one click it reads the text of the content you are editing, sends it to a large language model, and fills the content's tag field with relevant, on-topic tags — in the language you choose.

The module is **LLM-agnostic**: a single configuration key switches between **Anthropic (Claude)**, **OpenAI (GPT)** and **DeepSeek**. There are no vendor SDKs bundled — every provider is called over plain HTTPS — and new providers can be added by registering a small OSGi service, so you are never locked in.

It is built to Jahia's security and integration standards: the action is POST-only, runs with the editor's own permissions (no privilege escalation), is protected by the CSRF guard, and never ships or logs your API key.

**Key features**

- One-click **Auto Tagging** action in the Content Editor 3-dots menu.
- **Choose your AI provider** — Anthropic, OpenAI or DeepSeek — with one config key. Model, endpoint, token limit, temperature and prompt are all configurable per provider.
- **Pick the tag language** at generation time, independently of the content language.
- **Choose how many tags** (up to the configured maximum) and whether to **replace** the existing tags or **add to** them, right in the dialog.
- Tags are written to Jahia's standard tagging field (`j:tagList`), so they work with existing facets, queries and tag-based navigation.
- The action appears only on taggable content and only when the module is enabled on the site.
- **English and French** user interface.
- Secure by design: POST-only endpoint, caller-session reads, CSRF-protected, no key in the bundle, content logged at debug level only.

**Requirements**

- Jahia 8.2.
- An API key for at least one supported provider (Anthropic, OpenAI or DeepSeek). Model usage is billed by the provider you choose.

## Installation

1. **Install the module.** In Jahia Administration → Modules, upload the module JAR (or install it directly from the Jahia Store), then start it.
2. **Enable it on your site.** In Administration → Modules, add *Automatic Content Tags* to the site(s) where you want the action available. (The Auto Tagging action only appears on sites where the module is enabled.)
3. **Configure your AI provider.** Create the file `org.jahia.se.modules.contenttags.cfg` in `digital-factory-data/karaf/etc/` and set at least the active provider and its API key:

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
   #llm.tag.count.max=20
   #llm.tag.count.default=5
   # Style guidance only — the tag count and language come from the dialog.
   #llm.user.prompt=Respond ONLY with a JSON array of concise single- or two-word strings, without markdown, explanations or duplicates.
   ```

   The configuration is picked up automatically — no restart needed.
4. **Use it.** Open a content item in the Content Editor, open the 3-dots menu, click **Auto Tagging**, choose the tag language, the number of tags and whether to replace or add to existing tags, then click **Apply**. Save the content to persist the generated tags.

> Never commit a real API key to source control. The configuration shipped inside the module contains empty keys on purpose.

## FAQ

**Which AI providers are supported?**
Anthropic (Claude, Messages API), OpenAI (GPT, Chat Completions) and DeepSeek (Chat Completions). You pick one with the `llm.provider` key. Additional providers can be added by registering an `LlmProvider` OSGi service.

**Do I need an API key, and does it cost anything?**
Yes — you need a key from the provider you choose, and model usage is billed by that provider according to their pricing. The module itself is free (MIT).

**What content is sent to the AI provider?**
Only the internationalized text of the content item you are tagging, with HTML stripped and truncated to `llm.max.source.chars` (6000 by default). Nothing is sent until you click Apply. The module does not store your content or the responses.

**Which content types can be tagged?**
Standard content, pages and main-resource content (`jnt:content`, `jnt:page`, `jmix:mainResource`). The action is hidden on other node types and on sites where the module is not enabled.

**Does it tag unsaved changes?**
No. Tags are generated from the last saved version of the content. Save your edits first, then run Auto Tagging.

**Where are the tags stored?**
In Jahia's standard tag list (`j:tagList` via the `jmix:tagged` mixin), so they integrate with existing tag facets, queries and navigation.

**Does it replace existing tags?**
Your choice. The dialog has a **"Replace existing tags"** checkbox — checked replaces the current tag list, unchecked adds the generated tags to it (duplicates are removed).

**Can I control how many tags are generated?**
Yes. Set the **number of tags** in the dialog on each run. An administrator caps the maximum with `llm.tag.count.max` (default 20, up to 50) and sets the fallback with `llm.tag.count.default`; requests are clamped to the configured maximum. To tune the *style* of the tags, edit `llm.user.prompt` — but leave the count and language to the dialog, which the module injects authoritatively.

**In which language are the tags generated?**
You choose the tag language in the dialog at generation time; it is independent of the content's edit language.

**Is my API key safe?**
The key lives only in the OSGi configuration file on the server (`karaf/etc`), is never included in the module bundle, and is never written to the logs.

**Does it work in production / clustered environments?**
Yes. It is a standard OSGi module with no special infrastructure requirements beyond outbound HTTPS access to your chosen provider's API.

## Tags / keywords

`ai`, `llm`, `tagging`, `tags`, `taxonomy`, `metadata`, `content-editor`, `jcontent`, `automation`, `productivity`, `anthropic`, `claude`, `openai`, `gpt`, `deepseek`, `nlp`, `seo`, `content-management`, `ui-extension`
