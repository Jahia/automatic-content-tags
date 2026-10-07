package org.jahia.se.modules.contenttags.service.internal;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the raw LLM response into a tag list.
 *
 * <p>The prompt asks for a JSON array of strings; models occasionally wrap it in a
 * markdown code fence or fall back to comma-separated text, so both are tolerated.</p>
 */
final class TagResponseParser {

    private static final int MAX_TAG_LENGTH = 64;

    private TagResponseParser() {
    }

    /**
     * @param rawResponse the raw assistant text
     * @param maxTags     the maximum number of tags to keep (the model may over-generate)
     * @return the extracted tags, deduplicated, length-capped and limited to {@code maxTags};
     *         empty when nothing parses
     */
    static List<String> parse(String rawResponse, int maxTags) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return List.of();
        }
        String cleaned = rawResponse
                .replaceAll("(?s)```(?:json)?\\s*", "")
                .replaceAll("\\s*```", "")
                .trim();

        List<String> tags = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(cleaned);
            for (int i = 0; i < array.length(); i++) {
                addTag(tags, array.optString(i, ""), maxTags);
            }
        } catch (JSONException e) {
            // Fall back to comma-separated text
            for (String candidate : cleaned.split(",")) {
                String tag = candidate.replaceAll("^[\\[\"\\s]+|[\\]\"\\s]+$", "");
                addTag(tags, tag, maxTags);
            }
        }
        return tags;
    }

    private static void addTag(List<String> tags, String candidate, int maxTags) {
        String tag = candidate == null ? "" : candidate.trim();
        if (tag.isEmpty() || tags.contains(tag) || tags.size() >= maxTags) {
            return;
        }
        if (tag.length() > MAX_TAG_LENGTH) {
            tag = tag.substring(0, MAX_TAG_LENGTH).trim();
        }
        tags.add(tag);
    }
}
