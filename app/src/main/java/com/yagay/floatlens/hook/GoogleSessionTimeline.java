package com.yagay.floatlens.hook;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Compact structured timeline and invariant checker for one Google CTS generation. */
final class GoogleSessionTimeline {
    private static final int MAX_ENTRIES = 256;

    private long generation;
    private String token = "";
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> violations = new ArrayList<>();
    private int dropped;

    static final class Entry {
        final int sequence;
        final String event;
        final GoogleCtsSessionState.Phase phase;
        final String message;

        Entry(int sequence, String event, GoogleCtsSessionState.Phase phase, String message) {
            this.sequence = sequence;
            this.event = event == null ? "" : event;
            this.phase = phase == null ? GoogleCtsSessionState.Phase.IDLE : phase;
            this.message = message == null ? "" : message;
        }
    }

    synchronized void reset(long generation, String token) {
        this.generation = generation;
        this.token = token == null ? "" : token;
        entries.clear();
        violations.clear();
        dropped = 0;
    }

    synchronized void record(
            int sequence, String event, GoogleCtsSessionState.Phase phase, String message) {
        if (entries.size() >= MAX_ENTRIES) {
            dropped++;
            return;
        }
        Entry entry = new Entry(sequence, event, phase, message);
        entries.add(entry);
        validate(entry);
    }

    synchronized String summary() {
        Map<GoogleCtsSessionState.Phase, Integer> phases =
                new EnumMap<>(GoogleCtsSessionState.Phase.class);
        for (Entry entry : entries) {
            phases.put(entry.phase, phases.getOrDefault(entry.phase, 0) + 1);
        }
        String first = entries.isEmpty() ? "none" : entries.get(0).event;
        String last = entries.isEmpty() ? "none" : entries.get(entries.size() - 1).event;
        return "generation=" + generation
                + " token=" + shortToken(token)
                + " events=" + entries.size()
                + " dropped=" + dropped
                + " first=" + first
                + " last=" + last
                + " phases=" + phases
                + " violations=" + violations.size()
                + (violations.isEmpty() ? "" : " violationDetail=" + violations);
    }

    synchronized int violationCount() { return violations.size(); }

    private void validate(Entry entry) {
        if (entry.phase == GoogleCtsSessionState.Phase.IDLE
                || entry.phase == GoogleCtsSessionState.Phase.FINISHED) {
            violation("event_after_terminal:" + entry.event + "@" + entry.phase);
        }
        if (entry.event.contains("REGION_SELECTION_CONFIRMED")
                && entry.phase != GoogleCtsSessionState.Phase.CONFIRM_PENDING
                && entry.phase != GoogleCtsSessionState.Phase.SELECTING) {
            violation("confirm_in_phase:" + entry.phase);
        }
        if (entry.event.contains("QUERY_RESULT")
                && entry.phase == GoogleCtsSessionState.Phase.ACTIVE) {
            violation("result_before_payload");
        }
    }

    private void violation(String value) {
        if (value == null || value.isBlank() || violations.contains(value)) return;
        if (violations.size() < 16) violations.add(value);
    }

    private static String shortToken(String value) {
        if (value == null || value.isBlank()) return "none";
        return value.substring(0, Math.min(8, value.length()));
    }
}
