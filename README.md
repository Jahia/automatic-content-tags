# Automatic Content Tags - Jahia Content Editor UI Extension

This module uses AI to write two kinds of metadata in Jahia:

- **Tags**: an "Auto Tagging" action in the Content Editor fills `j:tagList` with tags generated from
  the text of the content.
- **Alternative text of images**: a GraphQL mutation writes the `jahia_alt` property of an image, or
  of every image below a folder, with a vision model that looks at the picture.

The module reaches its models through [genai-connector](https://github.com/Jahia/genai-connector).
It holds no provider, model or API key of its own: the operator configures them once, in
genai-connector, for the whole platform.

Compatible with Jahia 8.2 (genai-connector requires 8.2.2). Requires Java 17 to build.

## Requirements

- [genai-connector](https://github.com/Jahia/genai-connector) installed and started, with a model
  configured for the tier that each feature uses (`lite` by default).
- For the alternative text, the model of that tier must accept images, and genai-connector must
  support images on completions (`CompletionRequest.Builder.images`).
- `graphql-dxm-provider`, which every Jahia 8.2 ships.

## Features

- **Content Editor integration**: an "Auto Tagging" action in the Content Editor 3-dots menu.
- **AI-powered tagging**: extracts the internationalized text of the selected JCR node (through the
  calling user's session) and asks the model for relevant tags in the language chosen by the editor.
- **Editor controls**: choose the **number of tags** (default 5, up to the admin-configured
  `llm.tag.count.max`) and whether to **replace** the existing tags or **add to** them.
- **Alternative text of images**: the mixin `jmix:jahiaAlt` extends `jmix:image` with the i18n
  property `jahia_alt`. The GraphQL mutation `generateImageAltText` fills it for one image, or for
  every image below a path.
- **One AI configuration**: provider, model and key come from genai-connector, which also applies
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

1. Build and install genai-connector first (`mvn clean install` in its checkout), because no Maven
   repository holds it yet.
2. Build the module:
   ```bash
   mvn clean install
   ```
3. Deploy genai-connector, then `target/automatic-content-tags-<version>.jar`, to your Jahia
   instance (module management UI or `digital-factory-data/modules`).
4. Configure a model for the `lite` tier in genai-connector (`org.jahia.modules.genai.cfg`). See the
   genai-connector README.

## Configuration

`org.jahia.se.modules.contenttags.cfg` in `digital-factory-data/karaf/etc/`. Every key is optional;
the defaults are shown.

```properties
# --- Tags ---
llm.tier=lite                 # genai-connector tier: lite | balanced | advanced
llm.tag.count.max=20          # most tags a request may generate (hard-capped at 50)
llm.tag.count.default=5       # tag count when the dialog sends none
llm.max.tokens=1024
llm.max.source.chars=6000     # characters of content text sent to the model
llm.temperature=              # empty = provider default; also used for the alternative text
#llm.user.prompt=Respond ONLY with a JSON array of concise single- or two-word strings, without markdown, explanations or duplicates.

# --- Alternative text ---
alt.tier=lite                 # its model must accept images
alt.max.length=150            # characters of one alternative text (20 to 1000)
alt.max.images=20             # images sent to the model per call (1 to 500)
alt.max.image.bytes=5242880   # a larger original is replaced by its Jahia thumbnail
alt.max.tokens=300
#alt.prompt=                  # replaces the built-in instructions
```

> The number of tags and the tag language come from the dialog on every request and are injected
> authoritatively by the module. Keep `llm.user.prompt` to style guidance only.

**Upgrading from 1.x**: the keys `llm.provider` and `<provider>.api.key`, `.model` and `.base.url`
are no longer read. Configure the provider and the key in genai-connector instead, and remove them
from this file.

## Usage

### Tags

1. Open a content item in the Content Editor.
2. Open the 3-dots menu and click **Auto Tagging**.
3. Select the tag language, set how many tags you want (default 5), and choose whether to
   **replace existing tags** (checked) or **add to them** (unchecked).
4. Click **Apply**. The tag field is filled with the generated tags; save to persist them.

> Tags are generated from the **saved** content of the node. Save first, then generate tags.

### Alternative text of images

Call the mutation on `/modules/graphql` as an authenticated editor:

```graphql
mutation {
  generateImageAltText(path: "/sites/digitall/files/images", language: "en",
                       includeSubNodes: true, overwrite: false) {
    generated skipped failed truncated
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

- The text is written in the edit workspace, in the i18n property `jahia_alt` of the mixin
  `jmix:jahiaAlt`. Nothing is published.
- Each image is saved on its own. When a call reaches `alt.max.images` or the rate limit of
  genai-connector, it stops with `truncated: true`; call it again to continue, since the images done
  are skipped.
- The model receives the original file when it is a JPEG, PNG, GIF or WebP of at most
  `alt.max.image.bytes`, otherwise the thumbnail Jahia generated for the image. An image with neither
  is reported `FAILED`.
- Read the text back with `property(name: "jahia_alt", language: "en")`, or edit it in the Content
  Editor, where it shows as the optional "Alternative text" section of an image.

## Security notes

- The tagging actions accept **POST only**, require an authenticated user with `jcr:write_default`
  permission on the node, and read content strictly through the caller's JCR session.
- The mutation refuses the guest user, runs under the caller's session, lists only the images that
  user can read, and makes no model call for an image that user cannot edit (`jcr:write_default`).
- The CSRF guard whitelist covers only `*.generateContentTagsAction.do` and `*.contentTagsConfig.do`;
  `/modules/graphql` keeps its own protections.
- Content text and image bytes go to the model of the configured genai-connector tier. Content text is
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
