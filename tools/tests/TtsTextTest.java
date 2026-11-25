package com.dongting.player;

import java.util.Arrays;
import java.util.List;

/** Plain Java tests: javac -encoding UTF-8 -d build/reader-tests ...; java -cp ... */
public final class TtsTextTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void sentences(String text, String... expected) {
        List<TtsText.Sentence> actual = TtsText.sentences(text);
        check(actual.size() == expected.length, "Sentence count for " + text + ": " + actual.size());
        for (int i = 0; i < expected.length; i++) {
            check(expected[i].equals(actual.get(i).text(text)), "Sentence " + i + " for " + text);
            check(TtsText.sentenceAt(text, actual.get(i).start).start == actual.get(i).start, "Offset round trip");
        }
    }

    public static void main(String[] args) {
        sentences("第一句。第二句！第三句？", "第一句。", "第二句！", "第三句？");
        sentences("他说：“你好！”然后离开。", "他说：“你好！”", "然后离开。");
        sentences("价格3.14元。Version 1.2 works. Next!", "价格3.14元。", "Version 1.2 works.", "Next!");
        sentences("第一行\n第二行；第三行……结束。", "第一行", "第二行；", "第三行……", "结束。");
        sentences("\n \t");
        check(TtsText.splitChunks("").isEmpty(), "Blank files must not create empty utterances");
        String original = "第一句，不能在逗号就切段。".repeat(75);
        List<String> chunks = TtsText.splitChunks(original);
        check(String.join("", chunks).equals(original), "Paragraph splitter must preserve source text");
        check(chunks.get(0).length() >= 240 && chunks.get(0).length() <= 480, "Legacy paragraph size");
        String longText = "字".repeat(179) + "😀" + "尾".repeat(510);
        for (TtsText.Sentence sentence : TtsText.sentences(longText)) {
            String part = sentence.text(longText);
            check(!Character.isHighSurrogate(part.charAt(part.length() - 1)), "Do not split emoji");
            check(part.length() <= 180, "Unpunctuated input must fit external TTS limit");
        }
        String text = "第一句。第二句。第三句。";
        int second = text.indexOf("第二");
        String window = TtsText.readingWindow(Arrays.asList(text), 0, second);
        check(window.contains("第一句。\n▶ 第二句。\n第三句。"), "Previous/current/next sentence window");
        window = TtsText.readingWindow(Arrays.asList("上一段。", "本段。", "下一段。"), 1, 0);
        check(window.contains("上一段。\n▶ 本段。\n下一段。"), "Window crosses paragraph boundaries");
        check(TtsText.sentenceAt(text, second - 1).text(text).equals("第一句。"), "Punctuation belongs to prior sentence");
        check(TtsText.sentenceAt(text, second).text(text).equals("第二句。"), "Next utterance starts at sentence boundary");
        System.out.println("PASS: sentence punctuation, quotes, decimals, blank input, legacy paragraph indices, emoji and reading windows");
    }
}
