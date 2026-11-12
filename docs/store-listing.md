# Jahia Store listing — Automatic Content Tags

Source copy for the store.jahia.com listing. Keep in sync with the module version.

- **Module name:** Automatic Content Tags
- **Module id:** automatic-content-tags
- **Version:** 2.0.0
- **Compatibility:** Jahia 8.2
- **License:** MIT
- **Author:** Jahia Solutions Group
- **Source:** https://github.com/Jahia/automatic-content-tags
- **Suggested category:** Editor tools / Productivity / AI

---

## Short description (one line)

Generate content tags and image alternative text with AI, through Jahia's genai-connector.

## Description

Automatic Content Tags adds an **Auto Tagging** action to the Jahia Content Editor. In one click it reads the text of the content you are editing, sends it to a large language model, and fills the content's tag field with relevant, on-topic tags — in the language you choose.

It also writes the **alternative text of images**: a GraphQL mutation sends each image to a vision model and saves its description in the image's `jahia_alt` property, for one image or for a whole folder.

The module reaches its models through **genai-connector**, Jahia's shared AI connector. The provider, the model and the API key are configured once in genai-connector, for every AI feature of the platform: the Jahia AI gateway, Anthropic, OpenAI, Mistral, Azure OpenAI, Amazon Bedrock or any OpenAI-compatible endpoint.

It is built to Jahia's security and integration standards: the action is POST-only, runs with the editor's own permissions (no privilege escalation), is protected by the CSRF guard, and never ships or logs your API key.

**Key features**

- One-click **Auto Tagging** action in the Content Editor 3-dots menu.
- **Alternative text of images** through the GraphQL mutation `generateImageAltText`, for one image or every image below a path, in any site language.
- **One AI configuration** in genai-connector: provider, model, key, rate limit and audit log.
- **Pick the tag language** at generation time, independently of the content language.
- **Choose how many tags** (up to the configured maximum) and whether to **replace** the existing tags or **add to** them, right in the dialog.
- Tags are written to Jahia's standard tagging field (`j:tagList`), so they work with existing facets, queries and tag-based navigation.
- The action appears only on taggable content and only when the module is enabled on the site.
- **English and French** user interface.
- Secure by design: POST-only endpoint, caller-session reads, CSRF-protected, no key in the bundle, content logged at debug level only.

**Requirements**

- Jahia 8.2.2 or later.
- genai-connector, with a model configured for the `lite` tier. For the alternative text, that model must accept images. Model usage is billed by the provider configured in genai-connector.

## Installation

1. **Install the module.** In Jahia Administration → Modules, upload the module JAR (or install it directly from the Jahia Store), then start it.
2. **Enable it on your site.** In Administration → Modules, add *Automatic Content Tags* to the site(s) where you want the action available. (The Auto Tagging action only appears on sites where the module is enabled.)
3. **Configure AI in genai-connector.** Set the provider, the model and the key of the `lite` tier in `org.jahia.modules.genai.cfg` (see the genai-connector documentation). Optionally tune this module in `org.jahia.se.modules.contenttags.cfg` in `digital-factory-data/karaf/etc/`:

   ```properties
   # genai-connector tiers of each feature (defaults shown)
   #llm.tier=lite
   #alt.tier=lite
   #llm.max.tokens=1024
   #llm.max.source.chars=6000
   #llm.tag.count.max=20
   #llm.tag.count.default=5
   # Style guidance only — the tag count and language come from the dialog.
   #llm.user.prompt=Respond ONLY with a JSON array of concise single- or two-word strings, without markdown, explanations or duplicates.
   ```

   The configuration is picked up automatically — no restart needed.
4. **Use it.** Open a content item in the Content Editor, open the 3-dots menu, click **Auto Tagging**, choose the tag language, the number of tags and whether to replace or add to existing tags, then click **Apply**. Save the content to persist the generated tags.

> This module holds no API key: the key lives only in the genai-connector configuration on the server.

## FAQ

**Which AI providers are supported?**
Every provider of genai-connector: the Jahia AI gateway, Anthropic, OpenAI, Mistral, Azure OpenAI, Amazon Bedrock and any OpenAI-compatible endpoint. The operator chooses it in genai-connector.

**How is the alternative text of an image generated?**
The GraphQL mutation `generateImageAltText` sends the image (or its Jahia thumbnail when the original is too large or in another format) to the vision model of the configured tier, and saves the answer in the i18n property `jahia_alt` of the image, in the edit workspace. It skips an image that already has a text unless `overwrite` is true.

**Do I need an API key, and does it cost anything?**
genai-connector needs one for its provider, and model usage is billed by that provider. The module itself is free (MIT).

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
This module never sees it. The key lives only in the genai-connector configuration on the server.

**Does it work in production / clustered environments?**
Yes. It is a standard OSGi module with no special infrastructure requirements beyond genai-connector and its access to the provider.

## Tags / keywords

`ai`, `llm`, `tagging`, `tags`, `taxonomy`, `metadata`, `content-editor`, `jcontent`, `automation`, `productivity`, `genai`, `alt-text`, `accessibility`, `images`, `nlp`, `seo`, `content-management`, `ui-extension`
