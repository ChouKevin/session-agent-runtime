package com.java.system.sessionagent.slack.domain;

import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

/** Splits Slack text for presentation without changing the stored terminal answer. */
public final class SlackTextParts {

    public static final int MAXIMUM_UTF16_UNITS = 4_000;
    private static final String FENCE = "```";
    private static final String OPEN_FENCE = FENCE + "\n";
    private static final String CLOSE_FENCE = "\n" + FENCE;

    private SlackTextParts() {
    }

    public static List<String> split(String text) {
        Assert.hasText(text, "Slack terminal text must not be blank");
        List<String> parts = new ArrayList<>();
        int start = 0;
        boolean insideFence = false;
        while (start < text.length()) {
            String prefix = insideFence ? OPEN_FENCE : "";
            int end = preferredBoundary(text, start, MAXIMUM_UTF16_UNITS - prefix.length());
            boolean endsInsideFence = fenceState(text, start, end, insideFence);
            boolean finalPart = end == text.length();
            String suffix = endsInsideFence ? CLOSE_FENCE : "";
            if (prefix.length() + end - start + suffix.length() > MAXIMUM_UTF16_UNITS) {
                end = preferredBoundary(text, start, MAXIMUM_UTF16_UNITS - prefix.length() - suffix.length());
                endsInsideFence = fenceState(text, start, end, insideFence);
                finalPart = end == text.length();
                suffix = endsInsideFence ? CLOSE_FENCE : "";
            }
            parts.add(prefix + text.substring(start, end) + suffix);
            start = end;
            insideFence = endsInsideFence;
            if (finalPart) {
                break;
            }
        }
        return List.copyOf(parts);
    }

    private static int preferredBoundary(String text, int start, int capacity) {
        if (start + capacity >= text.length()) {
            return text.length();
        }
        int maximumEnd = safeBoundary(text, start, Math.min(text.length(), start + capacity));
        int newline = text.lastIndexOf('\n', maximumEnd - 1);
        if (newline >= start) {
            int newlineEnd = safeBoundary(text, start, newline + 1);
            if (newlineEnd - start >= capacity / 2 && !text.substring(start, newlineEnd).isBlank()) {
                return newlineEnd;
            }
        }
        if (maximumEnd == start) {
            return safeBoundary(text, start, nextCodePointEnd(text, start));
        }
        return maximumEnd;
    }

    private static int safeBoundary(String text, int start, int end) {
        int boundary = end;
        if (boundary < text.length() && boundary > start && Character.isHighSurrogate(text.charAt(boundary - 1))
                && Character.isLowSurrogate(text.charAt(boundary))) {
            boundary--;
        }
        int fenceStart = text.lastIndexOf(FENCE, Math.max(start, boundary));
        if (fenceStart >= start && boundary > fenceStart && boundary < fenceStart + FENCE.length()) {
            boundary = fenceStart;
        }
        return boundary;
    }

    private static int nextCodePointEnd(String text, int start) {
        int next = start + 1;
        if (next < text.length() && Character.isHighSurrogate(text.charAt(start)) && Character.isLowSurrogate(text.charAt(next))) {
            return next + 1;
        }
        return next;
    }

    private static boolean fenceState(String text, int start, int end, boolean initialState) {
        boolean insideFence = initialState;
        int index = text.indexOf(FENCE, start);
        while (index >= start && index + FENCE.length() <= end) {
            insideFence = !insideFence;
            index = text.indexOf(FENCE, index + FENCE.length());
        }
        return insideFence;
    }
}
