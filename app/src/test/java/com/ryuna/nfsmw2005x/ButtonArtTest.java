package com.ryuna.nfsmw2005x;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ButtonArtTest {
    /** native's keys (native/prompt_icons.cpp, and touch.cpp's PREV / NEXT for unnamed triggers): every one an Icon
     *  (a key the overlay cannot draw would fall to the letter). */
    private static final String[] NATIVE_KEYS = {"ACCEPT", "BACK", "CANCEL", "EXIT", "RESTART", "STATS", "EYE",
        "EYE_OFF", "INFO", "HELP", "DELETE", "EDIT", "CART", "CASH", "MAP", "CAMERA", "OPTIONS", "PLAY", "LIST",
        "ZOOM_IN", "ZOOM_OUT", "ROTATE", "FORWARD", "SEND", "SORT", "FILTER", "SAVE", "CHAT", "PERSON", "PREV", "NEXT"};

    @Test
    public void iconForKeyMapsEveryNativeKey() {
        for (String k : NATIVE_KEYS) assertEquals(k, k, ButtonArt.iconForKey(k).name());
        assertNull(ButtonArt.iconForKey(""));
        assertNull(ButtonArt.iconForKey(null));
        assertNull(ButtonArt.iconForKey("NOT_AN_ICON"));
    }

    @Test
    public void keyWinsOverLabelAndPad() {
        // French "RECOMMENCER" knows no English word: the hash's key decides
        assertEquals(ButtonArt.Icon.RESTART, ButtonArt.iconFor("RESTART", "RECOMMENCER"));
        assertNull(ButtonArt.iconFor("", "RECOMMENCER"));
        // Pause_Controller's A is Back: the key, not the pad
        assertEquals(ButtonArt.Icon.BACK, ButtonArt.iconFor("BACK", "ACCEPT"));
        assertEquals(ButtonArt.Icon.STATS, ButtonArt.iconFor("", "RACE STATS"));
    }

    @Test
    public void textFallbackForTheUsersExamples() {
        assertEquals(ButtonArt.Icon.RESTART, ButtonArt.iconForPrompt("RANDOMIZE"));
        for (String s : new String[] {"RACE STATS", "STATISTICS", "VIEW STATS", "GAME STATS", "PLAYER STATS",
                "PERSONAL STATS", "TOGGLE BIO/STATS"})
            assertEquals(s, ButtonArt.Icon.STATS, ButtonArt.iconForPrompt(s));
        assertEquals(ButtonArt.Icon.EYE, ButtonArt.iconForPrompt("VIEW TUTORIAL"));
    }

    @Test
    public void textFallbackKeepsNearWordsApart() {
        assertEquals(ButtonArt.Icon.ACCEPT, ButtonArt.iconForPrompt("accept"));
        assertEquals(ButtonArt.Icon.BACK, ButtonArt.iconForPrompt("BACK"));
        assertEquals(ButtonArt.Icon.EXIT, ButtonArt.iconForPrompt("QUIT RACE"));
        assertEquals(ButtonArt.Icon.RESTART, ButtonArt.iconForPrompt("restart race"));
        assertEquals(ButtonArt.Icon.PLAY, ButtonArt.iconForPrompt("START RACE"));    // not RESTART
        assertEquals(ButtonArt.Icon.CART, ButtonArt.iconForPrompt("VIEW CART"));     // not VIEW CAR's EYE
        assertEquals(ButtonArt.Icon.EYE, ButtonArt.iconForPrompt("VIEW CAR"));
        assertEquals(ButtonArt.Icon.DELETE, ButtonArt.iconForPrompt("DELETE CAR"));  // not CART
        assertEquals(ButtonArt.Icon.LIST, ButtonArt.iconForPrompt("STANDINGS"));
        assertEquals(ButtonArt.Icon.ZOOM_IN, ButtonArt.iconForPrompt("Zoom In"));
        assertEquals(ButtonArt.Icon.ZOOM_OUT, ButtonArt.iconForPrompt("ZOOM OUT"));
        assertNull(ButtonArt.iconForPrompt("xyz"));
        assertNull(ButtonArt.iconForPrompt(""));
        assertNull(ButtonArt.iconForPrompt(null));
    }

    @Test
    public void pressedFillIsHalfAgainAsOpaque() {
        // the fill is rgba(12,13,16,.55): alpha 140
        assertEquals(98, ButtonArt.bodyAlpha(false, .7f));
        assertEquals(147, ButtonArt.bodyAlpha(true, .7f));
        assertEquals(140, ButtonArt.bodyAlpha(false, 1f));
        assertEquals(210, ButtonArt.bodyAlpha(true, 1f));
        assertEquals(255, ButtonArt.scaledAlpha(0xFFFFFFFF, 1.5f));
        assertEquals(0, ButtonArt.scaledAlpha(0xFFFFFFFF, -1f));
    }

    @Test
    public void everyIconHasPathData() {
        for (ButtonArt.Icon i : ButtonArt.Icon.values()) {
            String svg = ButtonArt.iconSvg(i);
            assertFalse(i + " has no path data", svg == null || svg.isEmpty());
            final int[] seen = new int[2];          // segments, elements
            int segs = ButtonArt.parse(svg, new ButtonArt.PathSink() {
                @Override public void begin(boolean fill, float strokeWidth) { seen[1]++; }
                @Override public void moveTo(float x, float y) { }
                @Override public void lineTo(float x, float y) { seen[0]++; }
                @Override public void cubicTo(float x1, float y1, float x2, float y2, float x, float y) { seen[0]++; }
                @Override public void quadTo(float x1, float y1, float x, float y) { seen[0]++; }
                @Override public void close() { }
            });
            assertTrue(i + " parses to no segment", segs > 0);
            assertEquals(i + " segment count", seen[0], segs);
            assertTrue(i + " has no element", seen[1] > 0);
        }
    }
}
