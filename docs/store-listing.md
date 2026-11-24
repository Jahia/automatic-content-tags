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

Generate content tags and image alternative text with AI, through Jahia's ai-assistant module.

## Description

Automatic Content Tags adds an **Auto Tagging** action to the Jahia Content Editor. In one click it reads the text of the content you are editing, sends it to a large language model, and fills the content's tag field with relevant, on-topic tags — in the language you choose. On an image, a vision model looks at the picture, so the tags describe what it shows.

It also writes the **alternative text of images**: a vision model looks at each image and its description is saved in the image's `jahia_ai_alt` property. A GraphQL mutation does it for one image or for a whole folder, and by default each newly uploaded image gets its text a few seconds after the upload, in every language of its site.

The module reaches its models through **ai-assistant**, which holds Jahia's shared access to AI models. The provider, the model and the API key are configured once in ai-assistant, for every AI feature of the platform: the Jahia AI gateway, Anthropic, OpenAI, Mistral, Azure OpenAI, Amazon Bedrock or any OpenAI-compatible endpoint.

It is built to Jahia's security and integration standards: the action is POST-only, runs with the editor's own permissions (no privilege escalation), is protected by the CSRF guard, and never ships or logs your API key.

**Key features**

- One-click **Auto Tagging** action in the Content Editor 3-dots menu.
- **Alternative text of images** through the GraphQL mutation `generateImageAltText`, for one image or every image below a path, in any site language, and at upload (`alt.generate.on.upload`, on by default).
- **Tags of an image** from its picture, with the same Auto Tagging action.
- **One AI configuration** in ai-assistant: provider, model, key, rate limit and audit log.
- **Pick the tag language** at generation time, independently of the content language.
- **Choose how many tags** (up to the configured maximum) and whether to **replace** the existing tags or **add to** them, right in the dialog.
- Tags are written to Jahia's standard tagging field (`j:tagList`), so they work with existing facets, queries and tag-based navigation.
- The action appears only on taggable content and images, and only when the module is enabled on the site.
- **English and French** user interface.
- Secure by design: POST-only endpoint, caller-session reads, CSRF-protected, no key in the bundle, content logged at debug level only.

**Requirements**

- Jahia 8.2.3.2 or later, which ai-assistant requires.
- ai-assistant, with a model for the `lite` tier, and a model that accepts images for the `vision` tier. The alternative text and the tags of an image use `vision`. Model usage is billed by the provider configured in ai-assistant. ai-assistant requires mcp-server; content-governance is optional for it. No Store entry holds ai-assistant yet: it is built from its repository.

## Installation

1. **Install the module.** In Jahia Administration → Modules, upload the module JAR (or install it directly from the Jahia Store), then start it.
2. **Enable it on your site.** In Administration → Modules, add *Automatic Content Tags* to the site(s) where you want the action available. (The Auto Tagging action only appears on sites where the module is enabled.)
3. **Configure AI in ai-assistant.** Set the provider, the model and the key of the `lite` and `vision` tiers in `org.jahia.modules.genai.cfg` (see the ai-assistant documentation). Optionally tune this module in `org.jahia.se.modules.contenttags.cfg` in `digital-factory-data/karaf/etc/`:

   ```properties
   # ai-assistant tier of the tags of a content (default shown); images always use vision
   #llm.tier=lite
   #image.max.bytes=5242880
   #llm.max.tokens=1024
   #llm.max.source.chars=6000
   #llm.tag.count.max=20
   #llm.tag.count.default=5
   # Style guidance only — the tag count and language come from the dialog.
   #llm.user.prompt=Respond ONLY with a JSON array of concise single- or two-word strings, without markdown, explanations or duplicates.
   # Alternative text of each uploaded image, in every site language (default shown).
   # It acts on every site of the instance: set it to false to generate a text only through the mutation.
   #alt.generate.on.upload=true
   ```

   The configuration is picked up automatically — no restart needed.
4. **Use it.** Open a content item or an image in the Content Editor, open the 3-dots menu, click **Auto Tagging**, choose the tag language, the number of tags and whether to replace or add to existing tags, then click **Apply**. Save the content to persist the generated tags.
5. **Check the result.** Upload an image to a site: a few seconds later its "AI alternative text" field holds a text in each site language. Review it before you publish.

> This module holds no API key: the key lives only in the ai-assistant configuration on the server.

## Installation (français)

1. **Installez le module.** Dans Administration → Modules, chargez le JAR du module, puis démarrez-le. Le module ai-assistant doit être installé et démarré avant lui.
2. **Activez-le sur votre site.** Dans Administration → Modules, ajoutez *Automatic Content Tags* aux sites où l'action doit être disponible. L'action Auto Tagging n'apparaît que sur les sites où le module est activé.
3. **Configurez l'IA dans ai-assistant.** Renseignez le fournisseur, le modèle et la clé des niveaux `lite` et `vision` dans `org.jahia.modules.genai.cfg`. Le modèle de `vision` doit accepter les images. Les réglages de ce module sont dans `org.jahia.se.modules.contenttags.cfg`, dans `digital-factory-data/karaf/etc/`. La configuration est prise en compte sans redémarrage.
4. **Choisissez si le texte alternatif est généré au téléversement.** Par défaut (`alt.generate.on.upload=true`), chaque image téléversée est envoyée au modèle, dans chaque langue de son site, sur tous les sites de l'instance. Mettez la clé à `false` pour ne générer un texte que par la mutation GraphQL `generateImageAltText`.
5. **Utilisez-le.** Ouvrez un contenu ou une image dans l'éditeur de contenu, ouvrez le menu à trois points, cliquez sur **Auto Tagging**, choisissez la langue et le nombre de tags, puis cliquez sur **Appliquer**. Enregistrez le contenu pour conserver les tags.
6. **Vérifiez.** Téléversez une image : quelques secondes plus tard, son champ « Texte alternatif IA » contient un texte dans chaque langue du site. Relisez-le avant de publier.

## FAQ

**Which AI providers are supported?**
Every provider of ai-assistant: the Jahia AI gateway, Anthropic, OpenAI, Mistral, Azure OpenAI, Amazon Bedrock and any OpenAI-compatible endpoint. The operator chooses it in ai-assistant.

**How is the alternative text of an image generated?**
The GraphQL mutation `generateImageAltText` sends the image (or its Jahia thumbnail when the original is too large or in another format) to the model of the ai-assistant tier `vision`, and saves the answer in the i18n property `jahia_ai_alt` of the image, in the edit workspace. It skips an image that already has a text unless `overwrite` is true. The same happens a few seconds after each upload, unless `alt.generate.on.upload` is `false`.

**Can I turn off the alternative text at upload?**
Yes: set `alt.generate.on.upload=false` in `org.jahia.se.modules.contenttags.cfg`. Each uploaded image still gets the "AI alternative text" field, empty, and the mutation fills it on demand. One image on a site with ten languages makes ten model calls, counted on the uploader's rate limit in ai-assistant.

**Do I need an API key, and does it cost anything?**
ai-assistant needs one for its provider, and model usage is billed by that provider. The module itself is free (MIT).

**What content is sent to the AI provider?**
For the tags of a content: its internationalized text, with HTML stripped and truncated to `llm.max.source.chars` (6000 by default), when you click Apply. For an image: its picture (the original, or its Jahia thumbnail), its file name and its title, for the tags when you click Apply and for the alternative text when the mutation runs. With `alt.generate.on.upload=true`, the default, the picture of each uploaded image is also sent without an action of the editor, on every site of the instance. The module stores the alternative text it receives in the image; it stores no tag until you save.

**Which content types can be tagged?**
Standard content, pages, main-resource content and images (`jnt:content`, `jnt:page`, `jmix:mainResource`, `jmix:image`). The action is hidden on other node types and on sites where the module is not enabled.

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
This module never sees it. The key lives only in the ai-assistant configuration on the server.

**Does it work in production / clustered environments?**
Yes. It is a standard OSGi module with no special infrastructure requirements beyond ai-assistant and its access to the provider.

## Tags / keywords

`ai`, `llm`, `tagging`, `tags`, `taxonomy`, `metadata`, `content-editor`, `jcontent`, `automation`, `productivity`, `genai`, `alt-text`, `accessibility`, `images`, `nlp`, `seo`, `content-management`, `ui-extension`
