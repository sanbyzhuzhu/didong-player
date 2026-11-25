package com.dongting.player;

import java.util.List;

/** Best-effort migration from the old ZIP-entry EPUB text ordering, without losing backups. */
final class BookProgress {
    private BookProgress() { }

    static TtsText.Position remap(String oldText, String newText, int chunkIndex, int offset) {
        List<String> oldChunks = TtsText.splitChunks(oldText);
        if (oldChunks.isEmpty() || chunkIndex < 0 || chunkIndex >= oldChunks.size()) return null;
        String chunk = oldChunks.get(chunkIndex);
        TtsText.Sentence sentence = TtsText.sentenceAt(chunk, Math.max(0, offset));
        String compactNew = compact(newText);
        String needle = compact(chunk);
        int match = compactNew.indexOf(needle);
        if (match >= 0) match += compact(chunk.substring(0, sentence.start)).length();
        else match = compactNew.indexOf(compact(sentence.text(chunk)));
        if (match < 0) return null;
        int count = 0;
        for (int i = 0; i < newText.length(); i++) {
            if (!space(newText.charAt(i)) && count++ == match)
                return TtsText.positionForOffset(newText, TtsText.splitChunks(newText), i);
        }
        return null;
    }

    private static String compact(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) if (!space(text.charAt(i))) out.append(text.charAt(i));
        return out.toString();
    }

    private static boolean space(char ch) { return Character.isWhitespace(ch) || ch == '\u00a0'; }
}
