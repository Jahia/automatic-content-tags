package org.jahia.se.modules.contenttags.service.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AltTextCleanerTest {

    @Test
    void plainAnswerIsKept() {
        assertEquals("A red bicycle against a brick wall.",
                AltTextCleaner.clean("A red bicycle against a brick wall.", 150));
    }

    @Test
    void quotesFencesLabelsAndLineBreaksAreRemoved() {
        assertEquals("A red bicycle.", AltTextCleaner.clean("\"A red bicycle.\"", 150));
        assertEquals("A red bicycle.", AltTextCleaner.clean("“A red bicycle.”", 150));
        assertEquals("A red bicycle.", AltTextCleaner.clean("```\nA red bicycle.\n```", 150));
        assertEquals("A red bicycle.", AltTextCleaner.clean("Alt text: A red bicycle.", 150));
        assertEquals("A red bicycle.", AltTextCleaner.clean("Alternative text - \"A red bicycle.\"", 150));
        assertEquals("A red bicycle by a wall.", AltTextCleaner.clean("A red bicycle\n  by a wall.", 150));
    }

    @Test
    void markupIsDroppedAndEntitiesDecoded() {
        assertEquals("Fish & chips on a plate.",
                AltTextCleaner.clean("<b>Fish &amp; chips</b> on a plate.<script>x()</script>", 150));
    }

    @Test
    void longTextIsCutOnAWordBoundary() {
        String cleaned = AltTextCleaner.clean("A very long description of a sunset over the sea, with boats", 30);

        assertTrue(cleaned.length() <= 30);
        assertEquals("A very long description of a.", cleaned);
    }

    @Test
    void aTextThatEndsOnALetterOrADigitGetsAPeriod() {
        assertEquals("Piscine au coucher du soleil.", AltTextCleaner.clean("Piscine au coucher du soleil", 150));
        assertEquals("A boat named Sea 2.", AltTextCleaner.clean("A boat named Sea 2", 150));
        assertEquals("Is it a cat?", AltTextCleaner.clean("Is it a cat?", 150));
        assertEquals("夕日のプール", AltTextCleaner.clean("夕日のプール", 150));
        assertEquals("Exactly ten.", AltTextCleaner.clean("Exactly ten", 12));
        assertEquals("Exactly ten", AltTextCleaner.clean("Exactly ten", 11));
        assertEquals("Ten chars.", AltTextCleaner.clean("Ten chars.", 10));
        assertEquals("A boat.", AltTextCleaner.clean("A boat on", 8));
    }

    @Test
    void emptyAnswersGiveAnEmptyText() {
        assertEquals("", AltTextCleaner.clean(null, 150));
        assertEquals("", AltTextCleaner.clean("  \"\"  ", 150));
    }
}
