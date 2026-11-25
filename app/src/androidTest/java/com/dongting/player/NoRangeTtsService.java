package com.dongting.player;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.util.Log;

/** Test APK only: produces 2.5 seconds of silence and deliberately no rangeStart. */
public final class NoRangeTtsService extends TextToSpeechService {
    private volatile boolean stopped;

    @Override protected String[] onGetLanguage() { return new String[]{"zho", "CHN", ""}; }
    @Override protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }
    @Override protected int onLoadLanguage(String lang, String country, String variant) {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }
    @Override protected void onStop() { stopped = true; }
    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopped = false;
        Log.i("ReaderTestTts", request.getCharSequenceText().toString());
        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
        byte[] silence = new byte[Math.min(1600, callback.getMaxBufferSize())];
        int remaining = 80000;
        while (remaining > 0 && !stopped) {
            int length = Math.min(silence.length, remaining);
            if (callback.audioAvailable(silence, 0, length) != TextToSpeech.SUCCESS) return;
            remaining -= length;
        }
        if (!stopped) callback.done();
    }
}
