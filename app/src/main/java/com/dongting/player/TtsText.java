package com.dongting.player;

import java.util.ArrayList;
import java.util.List;

/** Shared paragraph coordinates and sentence boundaries for every reader surface. */
final class TtsText {
    private TtsText() { }

    static final class Position {
        final int chunk;
        final int offset;
        Position(int chunk, int offset) { this.chunk = chunk; this.offset = offset; }
    }

    static Position positionForOffset(String text, List<String> chunks, int offset) {
        int cursor = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            int start = text.indexOf(chunk, cursor);
            if (start < 0) start = cursor;
            int end = start + chunk.length();
            if (offset < end || i == chunks.size() - 1) {
                int local = Math.max(0, Math.min(offset - start, Math.max(0, chunk.length() - 1)));
                return new Position(i, sentenceAt(chunk, local).start);
            }
            cursor = end;
        }
        return new Position(0, 0);
    }

    static final class Sentence {
        final int start;
        final int end;

        Sentence(int start, int end) {
            this.start = start;
            this.end = end;
        }

        String text(String source) { return source.substring(start, end); }
    }

    // Keep the original UI's paragraph indices so bookmarks/catalog entries remain valid.
    static List<String> splitChunks(String text) {
        List<String> chunks = new ArrayList<>();
        String normalized = (text == null ? "" : text).replace("\r\n", "\n").replace('\r', '\n').trim();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            builder.append(ch);
            boolean boundary = ch == '\n' || "。！？.!?".indexOf(ch) >= 0;
            boolean safeCut = !Character.isHighSurrogate(ch);
            if ((builder.length() >= 240 && boundary) || (builder.length() >= 480 && safeCut)) {
                addChunk(chunks, builder);
            }
        }
        addChunk(chunks, builder);
        return chunks;
    }

    private static void addChunk(List<String> chunks, StringBuilder builder) {
        String value = builder.toString().trim();
        if (!value.isEmpty()) chunks.add(value);
        builder.setLength(0);
    }

    static List<Sentence> sentences(String text) {
        List<Sentence> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
            if (start == text.length()) break;
            int end = start;
            int softBreak = -1;
            while (end < text.length()) {
                char ch = text.charAt(end++);
                if (Character.isWhitespace(ch) || "，,、：:".indexOf(ch) >= 0) softBreak = end;
                if (isTerminal(text, end - 1)) {
                    while (end < text.length() && (isTerminal(text, end)
                            || "”’\"'」』）)]】".indexOf(text.charAt(end)) >= 0)) end++;
                    break;
                }
                // Bound long unpunctuated sentences for both system TTS and MultiTTS.
                if (end - start >= 180) {
                    if (softBreak > start + 90) end = softBreak;
                    else if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                    break;
                }
            }
            int trimmedEnd = end;
            while (trimmedEnd > start && Character.isWhitespace(text.charAt(trimmedEnd - 1))) trimmedEnd--;
            if (trimmedEnd > start) result.add(new Sentence(start, trimmedEnd));
            start = end;
        }
        return result;
    }

    private static boolean isTerminal(String text, int index) {
        char ch = text.charAt(index);
        if ("。！？!?；;\n…".indexOf(ch) >= 0) return true;
        if (ch != '.') return false;
        // Decimal points and dots inside URLs/identifiers are not sentence endings.
        return index + 1 == text.length() || !Character.isLetterOrDigit(text.charAt(index + 1));
    }

    static Sentence sentenceAt(String text, int offset) {
        List<Sentence> list = sentences(text);
        for (Sentence sentence : list) {
            if (offset < sentence.end) return sentence;
        }
        return list.isEmpty() ? new Sentence(0, 0) : list.get(list.size() - 1);
    }

    static String readingWindow(List<String> chunks, int chunkIndex, int offset) {
        if (chunks == null || chunks.isEmpty()) return "";
        chunkIndex = Math.max(0, Math.min(chunkIndex, chunks.size() - 1));
        String text = chunks.get(chunkIndex);
        List<Sentence> list = sentences(text);
        if (list.isEmpty()) return "";
        int current = list.size() - 1;
        for (int i = 0; i < list.size(); i++) {
            if (offset < list.get(i).end) { current = i; break; }
        }
        String previous = current > 0 ? list.get(current - 1).text(text) : "";
        if (previous.isEmpty() && chunkIndex > 0) {
            String priorText = chunks.get(chunkIndex - 1);
            List<Sentence> prior = sentences(priorText);
            if (!prior.isEmpty()) previous = prior.get(prior.size() - 1).text(priorText);
        }
        String next = current + 1 < list.size() ? list.get(current + 1).text(text) : "";
        if (next.isEmpty() && chunkIndex + 1 < chunks.size()) {
            String nextText = chunks.get(chunkIndex + 1);
            List<Sentence> following = sentences(nextText);
            if (!following.isEmpty()) next = following.get(0).text(nextText);
        }
        StringBuilder window = new StringBuilder("逐句跟读  ")
                .append(chunkIndex + 1).append('/').append(chunks.size()).append(" 段 · ")
                .append(current + 1).append('/').append(list.size()).append(" 句\n");
        if (!previous.isEmpty()) window.append(previous.replace('\n', ' ')).append('\n');
        window.append("▶ ").append(list.get(current).text(text).replace('\n', ' '));
        if (!next.isEmpty()) window.append('\n').append(next.replace('\n', ' '));
        return window.toString();
    }
}
