package com.example.call_translator;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.agora.rtc2.RtcEngine;

public class FullDuplexCallManager {

    private static final String TAG = "FullDuplexCallManager";
    private static final int SAMPLE_RATE = 16000;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
    );

    public interface OnMessageListener {
        void onMessage(String original, String translated, boolean isMine);
    }

    private final Context context;
    private final RtcEngine agoraEngine;
    private final FirebaseFirestore db;
    private final String callId;
    private final TextToSpeech tts;
    private final String mySpeechCode;
    private final Locale myTtsLocale;
    private final OnMessageListener messageListener;

    private Translator myTranslator;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;

    private AudioRecord audioRecord;
    private volatile boolean isRunning = false;
    private volatile boolean isTtsSpeaking = false;

    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public FullDuplexCallManager(
            Context context,
            RtcEngine agoraEngine,
            FirebaseFirestore db,
            String callId,
            String mySpeechCode,
            String mySourceLang,
            String otherUserLang,
            Locale myTtsLocale,
            TextToSpeech tts,
            OnMessageListener listener) {

        this.context         = context;
        this.agoraEngine     = agoraEngine;
        this.db              = db;
        this.callId          = callId;
        this.mySpeechCode    = mySpeechCode;
        this.myTtsLocale     = myTtsLocale;
        this.tts             = tts;
        this.messageListener = listener;

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(mySourceLang)
                .setTargetLanguage(otherUserLang)
                .build();
        myTranslator = Translation.getClient(options);
    }

    // ── Start ────────────────────────────────────────────────────────────────

    public void start(Runnable onReady) {
        myTranslator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> {
                    isRunning = true;
                    setupSpeechRecognizer();
                    startAgoraAudioCapture();
                    // Start speech recognizer on main thread
                    mainHandler.postDelayed(() -> {
                        startListeningOnce();
                        if (onReady != null) onReady.run();
                    }, 500);
                })
                .addOnFailureListener(e ->
                        Log.e(TAG, "Model download failed: " + e.getMessage()));
    }

    public void stop() {
        isRunning = false;

        // Stop AudioRecord
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "AudioRecord stop error", e);
            }
            audioRecord = null;
        }

        audioExecutor.shutdownNow();

        // Stop SpeechRecognizer on main thread
        mainHandler.post(() -> {
            if (speechRecognizer != null) {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
                speechRecognizer = null;
            }
        });

        if (myTranslator != null) {
            myTranslator.close();
        }
    }

    // ── Agora Audio (separate from SpeechRecognizer) ─────────────────────────
    // AudioRecord feeds raw PCM to Agora only
    // SpeechRecognizer uses its own internal mic access independently

    private void startAgoraAudioCapture() {
        audioExecutor.execute(() -> {

            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            if (ActivityCompat.checkSelfPermission(context,
                    Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "RECORD_AUDIO permission not granted");
                return;
            }

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    BUFFER_SIZE * 4
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize");
                return;
            }

            audioRecord.startRecording();
            short[] readBuffer = new short[BUFFER_SIZE];

            while (isRunning) {
                int read = audioRecord.read(readBuffer, 0, readBuffer.length);
                if (read <= 0) continue;

                // Push to Agora only — voice goes to other user in real-time
                if (agoraEngine != null) {
                    byte[] pcmBytes = shortsToBytes(readBuffer, read);
                    agoraEngine.pushExternalAudioFrame(
                            pcmBytes, System.currentTimeMillis());
                }
            }
        });
    }

    // ── SpeechRecognizer Setup ────────────────────────────────────────────────

    private void setupSpeechRecognizer() {
        mainHandler.post(() -> {

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);

            speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, mySpeechCode);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, mySpeechCode);
            speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

            speechRecognizer.setRecognitionListener(new RecognitionListener() {

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> data =
                            results.getStringArrayList(
                                    SpeechRecognizer.RESULTS_RECOGNITION);

                    if (data != null && !data.isEmpty()) {
                        String spokenText = data.get(0).trim();

                        if (!spokenText.isEmpty()) {
                            translateAndSend(spokenText);
                            // next listen starts after translation completes
                            return;
                        }
                    }
                    // No result — listen again immediately
                    scheduleNextListen(300);
                }

                @Override
                public void onError(int error) {
                    // These errors just mean silence or timeout — not real errors
                    // ERROR_NO_MATCH = 7, ERROR_SPEECH_TIMEOUT = 6
                    // Just restart listening
                    if (isRunning) {
                        scheduleNextListen(300);
                    }
                }

                @Override public void onReadyForSpeech(Bundle p) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float v) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(Bundle b) {}
                @Override public void onEvent(int t, Bundle b) {}
            });
        });
    }

    private void startListeningOnce() {
        // Don't listen while TTS is speaking (avoid capturing our own TTS)
        if (isTtsSpeaking) {
            scheduleNextListen(500);
            return;
        }

        if (speechRecognizer == null || !isRunning) return;

        try {
            speechRecognizer.cancel();
            speechRecognizer.startListening(speechIntent);
        } catch (Exception e) {
            Log.e(TAG, "startListening error: " + e.getMessage());
            scheduleNextListen(500);
        }
    }

    private void scheduleNextListen(int delayMs) {
        if (!isRunning) return;
        mainHandler.postDelayed(this::startListeningOnce, delayMs);
    }

    // ── Translate and Send ────────────────────────────────────────────────────

    private void translateAndSend(String spokenText) {
        myTranslator.translate(spokenText)
                .addOnSuccessListener(translatedText -> {
                    sendToFirestore(spokenText, translatedText);
                    if (messageListener != null) {
                        messageListener.onMessage(spokenText, translatedText, true);
                    }
                    // Resume listening after translation done
                    scheduleNextListen(200);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Translation failed: " + e.getMessage());
                    scheduleNextListen(300);
                });
    }

    // ── Firestore ─────────────────────────────────────────────────────────────

    private void sendToFirestore(String original, String translated) {
        Map<String, Object> message = new HashMap<>();
        message.put("original",  original);
        message.put("translated", translated);
        message.put("sender",    FirebaseAuth.getInstance().getUid());
        message.put("timestamp", System.currentTimeMillis());

        db.collection("calls")
                .document(callId)
                .collection("messages")
                .add(message)
                .addOnFailureListener(e ->
                        Log.e(TAG, "Firestore error: " + e.getMessage()));
    }

    // ── TTS incoming ──────────────────────────────────────────────────────────

    public void speakIncoming(String translatedText) {
        if (tts == null || translatedText == null || translatedText.isEmpty()) return;

        // Earpiece mode — mic won't pick up TTS
        AudioManager am = (AudioManager)
                context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(false);
        }

        isTtsSpeaking = true;

        tts.setLanguage(myTtsLocale);
        tts.speak(
                translatedText,
                TextToSpeech.QUEUE_ADD,
                null,
                "tts_" + System.currentTimeMillis()
        );

        // ✅ Use UtteranceProgressListener to know exactly when TTS finishes
        tts.setOnUtteranceProgressListener(
                new android.speech.tts.UtteranceProgressListener() {

                    @Override
                    public void onStart(String utteranceId) {
                        isTtsSpeaking = true;
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        isTtsSpeaking = false;
                        // Resume mic after TTS finishes speaking
                        mainHandler.postDelayed(() -> startListeningOnce(), 200);
                    }

                    @Override
                    public void onError(String utteranceId) {
                        isTtsSpeaking = false;
                    }
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private byte[] shortsToBytes(short[] shorts, int length) {
        ByteBuffer bb = ByteBuffer.allocate(length * 2);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < length; i++) bb.putShort(shorts[i]);
        return bb.array();
    }
}