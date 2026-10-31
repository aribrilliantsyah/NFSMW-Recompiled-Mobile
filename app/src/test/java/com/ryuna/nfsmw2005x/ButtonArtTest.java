package com.ryuna.nfsmw2005x;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ButtonArtTest {
    @Test
    public void iconForPromptMapsKnownLabels() {
        assertEquals(ButtonArt.Icon.ACCEPT, ButtonArt.iconForPrompt("ACCEPT"));
        assertEquals(ButtonArt.Icon.ACCEPT, ButtonArt.iconForPrompt("CONTINUE"));
        assertEquals(ButtonArt.Icon.ACCEPT, ButtonArt.iconForPrompt("OK"));
        assertEquals(ButtonArt.Icon.BACK, ButtonArt.iconForPrompt("BACK"));
        assertEquals(ButtonArt.Icon.RESTART, ButtonArt.iconForPrompt("RESTART"));
        assertEquals(ButtonArt.Icon.RESTART, ButtonArt.iconForPrompt("RESTART RACE"));
        assertEquals(ButtonArt.Icon.STATS, ButtonArt.iconForPrompt("STATISTICS"));
        assertEquals(ButtonArt.Icon.STATS, ButtonArt.iconForPrompt("STATS"));
        assertEquals(ButtonArt.Icon.EXIT, ButtonArt.iconForPrompt("EXIT"));
        assertEquals(ButtonArt.Icon.EXIT, ButtonArt.iconForPrompt("QUIT"));
        assertEquals(ButtonArt.Icon.CANCEL, ButtonArt.iconForPrompt("CANCEL"));
        assertEquals(ButtonArt.Icon.ZOOM_IN, ButtonArt.iconForPrompt("ZOOM IN"));
        assertEquals(ButtonArt.Icon.ZOOM_OUT, ButtonArt.iconForPrompt("ZOOM OUT"));
        // case-insensitive
        assertEquals(ButtonArt.Icon.ACCEPT, ButtonArt.iconForPrompt("accept"));
        assertEquals(ButtonArt.Icon.ZOOM_IN, ButtonArt.iconForPrompt("Zoom In"));
        assertEquals(ButtonArt.Icon.RESTART, ButtonArt.iconForPrompt("restart race"));
        assertNull(ButtonArt.iconForPrompt("xyz"));
        assertNull(ButtonArt.iconForPrompt(null));
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
