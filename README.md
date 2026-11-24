# Automatic Content Tags - Jahia Content Editor UI Extension

This module uses AI to write two kinds of metadata in Jahia:

- **Tags**: an "Auto Tagging" action in the Content Editor fills `j:tagList` with tags generated from
  the text of the content. An image is tagged from its picture, with a vision model.
- **Alternative text of images**: a GraphQL mutation writes the `jahia_ai_alt` property of an image, or
  of every image below a folder, with a vision model that looks at the picture.

The module reaches its models through [ai-assistant](https://github.com/Jahia/ai-assistant), which
holds the former genai-connector. This module holds no provider, model or API key of its own: the
operator configures them once, in ai-assistant, for the whole platform.

Runs on Jahia 8.2.3.2 or later, which ai-assistant requires. Requires Java 17 to build.

## Requirements

- [ai-assistant](https://github.com/Jahia/ai-assistant) installed and started, with the tier
  `vision`. Each call that sends an image uses `vision`: the alternative text, and the tags of an
  image. The tags of other content use `llm.tier` (`lite` by default). ai-assistant requires
  mcp-server; content-governance is optional for it.
- The model of `vision` must accept images. With no `vision.*` key, ai-assistant uses its shared
  model for `vision`; the shipped `mistral/mistral-small-latest` accepts images. The
  `docs/genai/README.md` of ai-assistant states what each tier needs.
- The permission `genaiAccess` on the site, for each user who generates tags or alternative text.
  ai-assistant defines it and grants it to the editorial roles. Each model call names the site of
  its content, and ai-assistant refuses a user who does not hold the permission there. A content
  outside `/sites`, such as a file of a user, resolves to the system site.
- `graphql-dxm-provider`, which every Jahia 8.2 ships.

## Features

- **Content Editor integration**: an "Auto Tagging" action in the Content Editor 3-dots menu.
- **AI-powered tagging**: extracts the internationalized text of the selected JCR node (through the
  calling user's session) and asks the model for relevant tags in the language chosen by the editor.
  For an image (`jmix:image`), the model of `vision` also sees the picture, so the tags describe what
  the image shows. For example, a photo named `IMG_2041.jpg` with no title still gets `cat, sofa`.
- **Editor controls**: choose the **number of tags** (default 5, up to the admin-configured
  `llm.tag.count.max`) and whether to **replace** the existing tags or **add to** them.
- **Alternative text of images**: the mixin `jmix:jahiaAlt` extends `jmix:image` with the i18n
  property `jahia_ai_alt`. The GraphQL mutation `generateImageAltText` fills it for one image, or for
  every image below a path.
- **Configurable targets**: the property and mixin that receive the alternative text and the tags come
  from the configuration (`alt.property`, `alt.mixin`, `llm.tag.property`, `llm.tag.mixin`).
- **One AI configuration**: provider, model and key come from ai-assistant, which also applies
  the per-user rate limit, retries and the audit log.

## Architecture

```
src/main/java/org/jahia/se/modules/contenttags/
├── actions/                       # POST-only render actions of the tagging dialog (CSRF-whitelisted)
├── graphql/                       # Mutation.generateImageAltText and its result types
├── service/ContentTagsService     # Tag generation
├── service/AltTextService         # Alternative text generation
└── service/internal/              # Implementations, text extraction, response cleaning

src/main/resources/META-INF/definitions.cnd   # jmix:jahiaAlt (extends jmix:image)
src/javascript/                                # React 18 UI extension (Module Federation)
```

## Installation

1. Build and install ai-assistant first (`mvn clean install` in its checkout), because no Maven
   repository holds it yet.
2. Build the module:
   ```bash
   mvn clean install
   ```
3. Deploy ai-assistant, then `target/automatic-content-tags-<version>.jar`, to your Jahia
   instance (module management UI or `digital-factory-data/modules`). Uninstall genai-connector
   first, because ai-assistant exports the same packages.
4. Configure the model access of ai-assistant (`org.jahia.modules.genai.cfg`): a model for the
   `lite` tier, and a model that reads images for the `vision` tier. For example,
   `vision.model=mistral/mistral-medium-latest` sends the image calls to a larger gateway model.
   See the `docs/genai/README.md` of ai-assistant.

## Configuration

`org.jahia.se.modules.contenttags.cfg` in `digital-factory-data/karaf/etc/`. Every key is optional;
the defaults are shown.

```properties
# --- Tags ---
llm.tier=lite                 # ai-assistant tier of the tags of a content: lite | balanced | advanced
                              # (an image is tagged from its picture, on the tier vision)
llm.tag.property=j:tagList    # property that receives the tags
llm.tag.mixin=jmix:tagged     # mixin that declares it; empty when the content type declares it
llm.tag.count.max=20          # most tags a request may generate (hard-capped at 50)
llm.tag.count.default=5       # tag count when the dialog sends none
llm.max.tokens=1024
llm.max.source.chars=6000     # characters of content text sent to the model
llm.temperature=              # empty = provider default, for the tags
image.max.bytes=5242880       # largest image sent to the model, for the alternative text and the tags;
                              # a larger original is replaced by its Jahia thumbnail
#llm.user.prompt=Respond ONLY with a JSON array of concise single- or two-word strings, without markdown, explanations or duplicates.

# --- Alternative text ---
alt.generate.on.upload=true   # generate the text of each new image, in every site language
alt.property=jahia_ai_alt     # property that receives the text: a single-valued string
alt.mixin=jmix:jahiaAlt       # mixin added before writing; empty when the image types declare it
alt.max.length=150            # characters of one alternative text (20 to 1000)
alt.max.images=20             # images sent to the model per call (1 to 500)
alt.max.seconds=60            # no new model call after this time in one call (5 to 600)
alt.max.tokens=300
alt.temperature=              # empty = provider default, for the alternative text
#alt.prompt=                  # replaces the built-in instructions
```

> The number of tags and the tag language come from the dialog on every request and are injected
> authoritatively by the module. Keep `llm.user.prompt` to style guidance only.

**Choosing the target properties.** Each feature writes one property, given by a name and the mixin that
declares it. The module checks the pair on each node, against that exact property name, so a misspelled
name is refused rather than written as a stray property.

- Alternative text: `alt.property` must be a single-valued string. For example, `alt.property=jcr:description`
  with `alt.mixin=jmix:description` writes the text into the image description. An image where the pair is
  not valid is reported `FAILED`, without a model call.
- Tags: the dialog fills the Content Editor field of `llm.tag.property` and activates `llm.tag.mixin`. For
  example, `llm.tag.property=j:keywords` with `llm.tag.mixin=jmix:keywords` fills the keywords instead of the
  tags. A single-valued string receives the tags joined with ", ". When the pair is not valid on the content,
  the dialog says so and does not apply.
- A property name must not contain a hyphen: Jahia stores definitions without quotes and reads `a-b` as two
  properties.

**Upgrading from 1.x**: the keys `llm.provider` and `<provider>.api.key`, `.model` and `.base.url`
are no longer read. Configure the provider and the key in ai-assistant instead, and remove them
from this file.

**Moving from genai-connector**: ai-assistant reads the same configuration,
`org.jahia.modules.genai.cfg`, so each tier keeps its model. Uninstall genai-connector, install
ai-assistant, then install this module.

**Choosing the model of the images.** Set it in ai-assistant, with `vision.model` or a whole
`vision.*` connection. This module has no tier key for images. The keys `alt.tier` and
`alt.max.image.bytes` of earlier 2.0.0 snapshots are no longer read; `image.max.bytes` replaces the
second.

## Usage

### Tags

1. Open a content item, or an image, in the Content Editor.
2. Open the 3-dots menu and click **Auto Tagging**.
3. Select the tag language, set how many tags you want (default 5), and choose whether to
   **replace existing tags** (checked) or **add to them** (unchecked).
4. Click **Apply**. The tag field is filled with the generated tags; save to persist them.

> Tags are generated from the **saved** content of the node. Save first, then generate tags.

For an image, the model receives the original file or a Jahia thumbnail, as for the alternative text
below, with the file name and the saved text of the image. An image with no JPEG, PNG, GIF or WebP
rendition is tagged from its text only, on `llm.tier`.

### Alternative text of images

Call the mutation on `/modules/graphql` as an authenticated editor:

```graphql
mutation {
  generateImageAltText(path: "/sites/digitall/files/images", language: "en",
                       includeSubNodes: true, overwrite: false) {
    generated skipped failed truncated resumeFrom
    images { path status altText message }
  }
}
```

| Argument | Default | Meaning |
|---|---|---|
| `path` | required | An image (`jmix:image`), or a node above images when `includeSubNodes` is true |
| `language` | required | The language of the text, one of the site languages |
| `includeSubNodes` | `false` | Also process every image below `path` |
| `overwrite` | `false` | Replace an existing alternative text; otherwise such an image is `SKIPPED` |
| `resumeFrom` | none | The `resumeFrom` of an earlier report, to continue from that image |

- The text is written in the edit workspace, in the configured property: by default the i18n property
  `jahia_ai_alt` of the mixin `jmix:jahiaAlt`. Nothing is published.
- The name `jahia_ai_alt` marks the text as written by AI, and the help of the field in the Content
  Editor says that AI must fill it. A template or a script can therefore treat its value as AI-written.
- Each image is saved on its own, and the images are visited in path order. A call stops with
  `truncated: true` and a `resumeFrom` path when it reaches `alt.max.images` model calls or
  `alt.max.seconds`, at the rate limit of ai-assistant, or when the AI provider is busy or
  unavailable. Call it again with that `resumeFrom` to continue, also with `overwrite: true`.
- `images` lists the generated and failed images. Ask `images(includeSkipped: true)` to also list
  the skipped ones; `skipped` counts them in both cases.
- The model of the tier `vision` receives the original file when it is a JPEG, PNG, GIF or WebP of
  at most `image.max.bytes`, otherwise the thumbnail Jahia generated for the image. An image with neither
  is reported `FAILED`.
- The prompt also holds the file name and the `jcr:title` of the image, which help the model name the
  subject. An editor writes both, so they can hold instructions to the model. The answer is cleaned to
  one line of plain text and cut at `alt.max.length`, so it cannot add markup to a page.
- Read the text back with `property(name: "jahia_ai_alt", language: "en")`, or edit it in the Content
  Editor, where it shows as the "AI alternative text" field of an image.
- A rule (`META-INF/rules.drl`) adds `jmix:jahiaAlt` to each image when it is uploaded or replaced,
  on the same trigger as Jahia's own `jmix:image`, so the Content Editor shows the field on every image.
- With `alt.generate.on.upload=true` (the default), the same rule also queues the generation of the
  text. A few seconds after the upload, it is written in every language of the image's site, as the
  uploader, without replacing an existing text. The upload itself does not wait for the model, and
  the server log says what each language got. The
  Content Editor ignores `extends = jmix:image`, because an image's primary type is `jnt:file`. An
  image uploaded before the module gets the mixin when it is replaced, or when the mutation writes
  its text. The rule always adds `jmix:jahiaAlt`, even when `alt.mixin` names another mixin.

## Known limits of 2.0.0

- **The upload rule acts on every site of the instance**, also on a site where the module is not
  enabled. Each uploaded or replaced image gets `jmix:jahiaAlt`. With `alt.generate.on.upload=true`
  (the default), its picture is also sent to the model of the tier `vision`, once per language of its
  site, with no action of the editor. Set `alt.generate.on.upload=false` to generate a text only
  through the mutation.
- **The generation at upload counts on the uploader's rate limit.** An image on a site with ten
  languages makes ten model calls, and ai-assistant allows each user 60 calls per 10 minutes by default
  (`rate.limit.requests`). Once the limit is reached, the other languages and the next images get no
  text at upload. Run the mutation later: it skips the texts already written.
- **The module is built against ai-assistant `0.1.0-SNAPSHOT`** (its `main` of 24 November 2026),
  because ai-assistant has no release yet. It imports `org.jahia.modules.genai.api` in the range
  `[0.1,1)`.
- **Before an uninstall**, the images keep the mixin `jmix:jahiaAlt`, which the module defines. List them
  with the JCR-SQL2 query `SELECT * FROM [jmix:jahiaAlt]` and remove the mixin from them first, or keep
  the module installed.
- **No Maven repository and no Store entry holds ai-assistant yet.** Build it from its repository, as the
  installation steps say.

## Security notes

- With `alt.generate.on.upload=true` (the default), the picture of each uploaded image goes to the
  model without an action of the editor, on every site of the instance.
- The tagging actions accept **POST only**, require an authenticated user with `jcr:write_default`
  permission on the node, and read content strictly through the caller's JCR session.
- Each model call names the site of its content, so ai-assistant checks the permission `genaiAccess`
  of the calling user on that site and names the site in its audit log. A refused user gets HTTP 403
  from the tagging action, and a `FAILED` image from the mutation, without a model call.
- The mutation refuses the guest user, runs under the caller's session, lists only the images that
  user can read, and makes no model call for an image that user cannot edit (`jcr:write_default`).
- The CSRF guard whitelist covers only `*.generateContentTagsAction.do` and `*.contentTagsConfig.do`;
  `/modules/graphql` keeps its own protections.
- Content text and image bytes go to the model of the configured ai-assistant tier. Content text is
  truncated to `llm.max.source.chars`; the model answer is stored as plain text, without markup.

## Development

- Frontend: React 18 (see `src/javascript/AutoTags/`), built with Webpack/Module Federation.
- Backend: Java 17 OSGi Declarative Services; unit tests with JUnit 5 and Mockito.

Build with Java 17 (`JAVA_HOME` must point at a JDK 17). The `frontend-maven-plugin` runs the
Webpack build during `mvn install`.

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## License

Licensed under the [MIT License](LICENSE).
