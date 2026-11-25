package com.dongting.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ReadableBook {
    static final class Chapter {
        final String title;
        final int offset;
        final int depth;

        Chapter(String title, int offset, int depth) {
            this.title = title;
            this.offset = offset;
            this.depth = Math.max(0, Math.min(depth, 8));
        }
    }

    final String text;
    final List<Chapter> chapters;
    final List<String> warnings;
    final String format;
    final String legacyText;

    ReadableBook(String text, List<Chapter> chapters, List<String> warnings, String format, String legacyText) {
        this.text = text;
        this.chapters = Collections.unmodifiableList(new ArrayList<>(chapters));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        this.format = format;
        this.legacyText = legacyText;
    }
}
