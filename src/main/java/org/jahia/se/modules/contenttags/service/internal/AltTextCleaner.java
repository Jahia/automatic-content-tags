package org.jahia.se.modules.contenttags.service.internal;

import org.jsoup.Jsoup;

/**
 * Turns the raw answer of a model into an alternative text that can be saved as it is.
 *
 * <p>A model sometimes wraps its answer in quotes or a code fence, prefixes it with a label, or adds
 * markup. The text is stored in {@code jahia_alt} and rendered later inside an {@code alt}
 * attribute, so it must be one line of plain text no longer than the configured maximum.</p>
 */
final class AltTextCleaner {

    private static final String QUOTES = "\"'`“”‘’«»";

    private AltTextCleaner() {
    }

    /**
     * @param raw       the text the model returned
     * @param maxLength the most characters the result may have
     * @return plain text on one line, at most {@code maxLength} characters, possibly empty
     */
    static String clean(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        String text = raw.replaceAll("```[A-Za-z]*", " ");
        // Jsoup drops markup, decodes entities and collapses the whitespace, line breaks included.
        text = Jsoup.parse(text).text();
        text = text.replaceFirst("(?i)^(alternative text|alt[ -]?text|alt)\\s*[:\\-]\\s*", "");
        text = stripQuotes(text.strip());
        if (text.length() > maxLength) {
            String cut = text.substring(0, maxLength);
            int space = cut.lastIndexOf(' ');
            if (space > maxLength / 2) {
                cut = cut.substring(0, space);
            }
            text = cut.replaceAll("[\\s,;:\\-–—]+$", "");
        }
        return text;
    }

    private static String stripQuotes(String text) {
        String result = text;
        while (result.length() >= 2 && QUOTES.indexOf(result.charAt(0)) >= 0
                && QUOTES.indexOf(result.charAt(result.length() - 1)) >= 0) {
            result = result.substring(1, result.length() - 1).strip();
        }
        return result;
    }
}
