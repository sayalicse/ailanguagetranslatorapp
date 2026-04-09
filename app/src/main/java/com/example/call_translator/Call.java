package com.example.call_translator;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import com.example.call_translator.Adapter.MessageAdapter;
import com.example.call_translator.model.Message;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.mlkit.nl.translate.Translator;

import android.speech.tts.TextToSpeech;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.agora.rtc2.RtcEngine;
import io.agora.rtc2.RtcEngineConfig;

public class Call extends AppCompatActivity {

    private SpeechRecognizer speechRecognizer;
    private LinearLayout translationContainer;
    private Intent speechIntent;
    private boolean isListening = false;
    private Translator translator;
    private FirebaseFirestore db;
    private String callId;
    private FullDuplexCallManager fullDuplexManager;
    private Locale myTtsLocale = Locale.ENGLISH; // locale for TTS (what I will HEAR translated into)
    Spinner spLanguage, spUserLanguage,spUser;
    ArrayList<String> userNames = new ArrayList<>();
    ArrayList<String> userIds = new ArrayList<>();
    RecyclerView recyclerMessages;
    ArrayList<Message> messageList = new ArrayList<>();
    MessageAdapter adapter;
    private RtcEngine agoraEngine;
    private TextToSpeech textToSpeech;
    private static final String APP_ID = "4f9113b6eba94b958abdf54016474862";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupAgora();
        Button btnStartCall = findViewById(R.id.btnStartCall);
        Button btnEndCall = findViewById(R.id.btnEndCall);
        recyclerMessages = findViewById(R.id.recyclerMessages);

        adapter = new MessageAdapter(messageList);

        recyclerMessages.setLayoutManager(new LinearLayoutManager(this));
        recyclerMessages.setAdapter(adapter);
//        translationContainer = findViewById(R.id.translationContainer);
        spUserLanguage = findViewById(R.id.spUserLanguage);
        spLanguage = findViewById(R.id.spLanguage);
        spUser = findViewById(R.id.spUser);
        db = FirebaseFirestore.getInstance();
        translator = null;
        loadUsers();
        callId = getIntent().getStringExtra("callId");
        String[] languages = {"English","Hindi","Marathi"};

        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item,
                        languages);


        spUserLanguage.setAdapter(adapter);
        spLanguage.setAdapter(adapter);



        // Check microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);

        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);

        speechIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        speechIntent.putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        speechIntent.putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        speechIntent.putExtra(
                RecognizerIntent.EXTRA_PREFER_OFFLINE, false);


        speechRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onResults(Bundle results) {
                isRecognizerBusy = false; // ✅ reset flag

                ArrayList<String> data =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                if (data != null && !data.isEmpty()) {
                    String spokenText = data.get(0);

                    if (translator != null) {
                        translator.translate(spokenText)
                                .addOnSuccessListener(translatedText -> {
                                    sendMessage(spokenText, translatedText);
                                    addMessage("You: " + spokenText, true);
                                    addMessage("➜ " + translatedText, true);

                                    restoreAgoraAudio(); // ✅ restore Agora after speech done
                                    restartListening();
                                });
                    } else {
                        restartListening();
                    }
                }
            }
            @Override
            public void onReadyForSpeech(Bundle params) {
                Toast.makeText(Call.this,"Speak now...",Toast.LENGTH_SHORT).show();
            }

            @Override public void onBeginningOfSpeech() {}

            @Override public void onRmsChanged(float rmsdB) {}

            @Override public void onBufferReceived(byte[] buffer) {}

            // restart listening for continuous speech
            @Override
            public void onEndOfSpeech() {


            }

            // restart if error occurs
            @Override
            public void onError(int error) {
                isRecognizerBusy = false; // ✅ reset flag

                // Error 8 = busy, Error 5 = client, Error 7 = no match — all safe to retry
                if (isListening) {
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        // Longer delay for busy error
                        new android.os.Handler(getMainLooper()).postDelayed(
                                () -> restartListening(), 1000
                        );
                    } else {
                        restartListening();
                    }
                }
            }

            @Override public void onPartialResults(Bundle partialResults) {}

            @Override public void onEvent(int eventType, Bundle params) {}
        });

        btnStartCall.setOnClickListener(v -> {

            String receiverId   = userIds.get(spUser.getSelectedItemPosition());
            String callerLang   = spUserLanguage.getSelectedItem().toString(); // MY language
            String receiverLang = spLanguage.getSelectedItem().toString();     // receiver's language

            callId = db.collection("calls").document().getId();

            Map<String, Object> call = new HashMap<>();
            call.put("callerId",          FirebaseAuth.getInstance().getUid());
            call.put("receiverId",        receiverId);
            call.put("status",            "calling");
            call.put("timestamp",         System.currentTimeMillis());
            call.put("callerLanguage",    callerLang);    // e.g. "English"
            call.put("receiverLanguage",  receiverLang);  // e.g. "Hindi"

            db.collection("calls").document(callId).set(call);

            listenMessages();
            listenForCallEnd(callId);
            waitForCallAcceptance(callId);
        });
        btnEndCall.setOnClickListener(v -> {

            if(callId != null){
                db.collection("calls")
                        .document(callId)
                        .update("status","ended");
            }

            endCallLocally();
        });
        listenIncomingCall();
        textToSpeech = new TextToSpeech(this, status -> {
            if(status == TextToSpeech.SUCCESS){
                textToSpeech.setLanguage(Locale.ENGLISH);
            }
        });
    }
    private void endCallLocally() {
        if (fullDuplexManager != null) {
            fullDuplexManager.stop();
            fullDuplexManager = null;
        }
        if (agoraEngine != null) {
            agoraEngine.leaveChannel();
        }
        Toast.makeText(this, "Call Ended", Toast.LENGTH_SHORT).show();
    }
    private void loadUsers(){

        db.collection("users")
                .get()
                .addOnSuccessListener(query -> {

                    userNames.clear();
                    userIds.clear();

                    for (var doc : query.getDocuments()) {

                        if(!doc.getId().equals(FirebaseAuth.getInstance().getUid())){

                            userNames.add(doc.getString("name"));
                            userIds.add(doc.getId());
                        }
                    }

                    ArrayAdapter<String> adapter =
                            new ArrayAdapter<>(this,
                                    android.R.layout.simple_spinner_dropdown_item,
                                    userNames);

                    spUser.setAdapter(adapter);

                });
    }
    private void sendMessage(String originalText, String translatedText){

        String uid = FirebaseAuth.getInstance().getUid();

        Map<String,Object> message = new HashMap<>();
        message.put("original", originalText);
        message.put("translated", translatedText);
        message.put("sender", uid);
        message.put("timestamp", System.currentTimeMillis());

        db.collection("calls")
                .document(callId)
                .collection("messages")
                .add(message);
    }
    private void addMessage(String text, boolean isMine){

        messageList.add(new Message(text,isMine));

        adapter.notifyItemInserted(messageList.size()-1);

        recyclerMessages.scrollToPosition(messageList.size()-1);
    }
    private void listenMessages() {
        db.collection("calls")
                .document(callId)
                .collection("messages")
                .orderBy("timestamp")
                .addSnapshotListener((value, error) -> {
                    if (value == null) return;

                    for (DocumentChange dc : value.getDocumentChanges()) {
                        if (dc.getType() == DocumentChange.Type.ADDED) {

                            String sender     = dc.getDocument().getString("sender");
                            String original   = dc.getDocument().getString("original");
                            String translated = dc.getDocument().getString("translated");
                            String myId       = FirebaseAuth.getInstance().getUid();

                            if (sender != null && sender.equals(myId)) {
                                // My own message — already shown by FullDuplexManager callback
                            } else {
                                // Other user's message — show + speak in MY language
                                addMessage(translated, false);
                                if (fullDuplexManager != null) {
                                    fullDuplexManager.speakIncoming(translated);
                                }
                            }
                        }
                    }
                });
    }
    private void listenIncomingCall(){

        String uid = FirebaseAuth.getInstance().getUid();

        db.collection("calls")
                .whereEqualTo("receiverId", uid)
                .whereEqualTo("status","calling")
                .addSnapshotListener((value, error) -> {

                    if(value == null) return;

                    for (DocumentChange dc : value.getDocumentChanges()) {

                        if(dc.getType() == DocumentChange.Type.ADDED){

                            String callId = dc.getDocument().getId();

                            showIncomingCall(callId);
                        }
                    }
                });
    }
    private void showIncomingCall(String incomingCallId) {
        new AlertDialog.Builder(this)
                .setTitle("Incoming Call")
                .setMessage("Someone is calling you")
                .setPositiveButton("Accept", (dialog, which) -> {
                    db.collection("calls")
                            .document(incomingCallId)
                            .update("status", "accepted")
                            .addOnSuccessListener(aVoid -> {
                                // Only start call AFTER Firestore confirms update
                                startCall(incomingCallId);
                            });
                })
                .setNegativeButton("Reject", (dialog, which) -> {
                    db.collection("calls")
                            .document(incomingCallId)
                            .update("status", "rejected");
                })
                .show();
    }
    private void startCall(String callId) {
        this.callId = callId;

        listenMessages();
        listenForCallEnd(callId);

        db.collection("calls").document(callId).get()
                .addOnSuccessListener(doc -> {
                    if (doc == null || !doc.exists()) {
                        Toast.makeText(this, "Call not found", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String callerLang   = doc.getString("callerLanguage");
                    String receiverLang = doc.getString("receiverLanguage");
                    String myId         = FirebaseAuth.getInstance().getUid();
                    String callerId     = doc.getString("callerId");

                    if (callerLang == null || receiverLang == null || callerId == null) {
                        Toast.makeText(this, "Missing call data", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String myLang, otherLang;
                    if (myId.equals(callerId)) {
                        myLang    = callerLang;
                        otherLang = receiverLang;
                    } else {
                        myLang    = receiverLang;
                        otherLang = callerLang;
                    }

                    String mySpeechCode = getSpeechCode(myLang);
                    String mySourceLang = getLangCode(myLang);
                    String myTargetLang = getLangCode(otherLang);
                    myTtsLocale         = getTtsLocale(myLang); // ✅ update class-level variable

                    textToSpeech.setLanguage(myTtsLocale);

                    // ✅ Update speechIntent language BEFORE listening
                    speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, mySpeechCode);
                    speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, mySpeechCode);

                    // ✅ Build translator
                    TranslatorOptions options = new TranslatorOptions.Builder()
                            .setSourceLanguage(mySourceLang)
                            .setTargetLanguage(myTargetLang)
                            .build();
                    translator = Translation.getClient(options);

                    // ✅ Download model then start
                    DownloadConditions conditions = new DownloadConditions.Builder().build();
                    translator.downloadModelIfNeeded(conditions)
                            .addOnSuccessListener(unused -> {
                                // All on main thread — safe for SpeechRecognizer
                                if (agoraEngine != null) {
                                    agoraEngine.joinChannel(null, callId, "", 0);
                                }

                                fullDuplexManager = new FullDuplexCallManager(
                                        this,
                                        agoraEngine,
                                        db,
                                        callId,
                                        mySpeechCode,
                                        mySourceLang,
                                        myTargetLang,
                                        myTtsLocale,
                                        textToSpeech,
                                        (original, translated, isMine) ->
                                                runOnUiThread(() -> {
                                                    addMessage("You: " + original, true);
                                                    addMessage("➜ " + translated, true);
                                                })
                                );

                                fullDuplexManager.start(() ->
                                        runOnUiThread(() ->
                                                Toast.makeText(this,
                                                        "Call Connected ✓", Toast.LENGTH_SHORT).show()
                                        )
                                );

                                // ✅ Start mic immediately after call connects
                                startListeningSafely();
                            })
                            .addOnFailureListener(e ->
                                    Toast.makeText(this,
                                            "Model download failed: " + e.getMessage(),
                                            Toast.LENGTH_LONG).show()
                            );
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this,
                                "Failed to load call: " + e.getMessage(),
                                Toast.LENGTH_LONG).show()
                );
    }
    private void startListeningSafely() {
        if (speechRecognizer == null || isRecognizerBusy) return;

        isListening = true;

        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            try {
                releaseAgoraForSpeech(); // ✅ fully release mic

                new android.os.Handler(getMainLooper()).postDelayed(() -> {
                    // mic is now free for SpeechRecognizer
                    isRecognizerBusy = true;
                    speechRecognizer.cancel();
                    speechRecognizer.startListening(speechIntent);
                }, 500); // wait 500ms for Agora to release mic

            } catch (Exception e) {
                isRecognizerBusy = false;
                e.printStackTrace();
            }
        }, 1500);
    }
    private void releaseAgoraForSpeech() {
        if (agoraEngine != null) {
            agoraEngine.muteLocalAudioStream(true);
            agoraEngine.disableAudio(); // ✅ actually releases mic hardware
        }
    }

    private void restoreAgoraAudio() {
        if (agoraEngine != null) {
            agoraEngine.enableAudio(); // ✅ re-enable after speech done
            agoraEngine.muteLocalAudioStream(false);
        }
    }
    private String getLangCode(String lang){

        if(lang.equals("Hindi"))
            return TranslateLanguage.HINDI;

        if(lang.equals("Marathi"))
            return TranslateLanguage.MARATHI;

        return TranslateLanguage.ENGLISH;
    }
    private String getSpeechCode(String lang){

        if(lang.equals("Hindi"))
            return "hi-IN";

        if(lang.equals("Marathi"))
            return "mr-IN";

        return "en-US";
    }
    private Locale getTtsLocale(String lang){

        if(lang.equals("Hindi"))
            return new Locale("hi", "IN");

        if(lang.equals("Marathi"))
            return new Locale("mr", "IN");

        return Locale.ENGLISH;
    }
    private void waitForCallAcceptance(String callId) {
        db.collection("calls")
                .document(callId)
                .addSnapshotListener((value, error) -> {
                    if (value == null) return;
                    String status = value.getString("status");
                    if ("accepted".equals(status)) {
                        // Don't call listenForCallEnd here — startCall() already does it
                        startCall(callId);
                    }
                });
    }
    private void setupAgora() {

        try {

            RtcEngineConfig config = new RtcEngineConfig();
            config.mContext = this;
            config.mAppId = APP_ID;

            agoraEngine = RtcEngine.create(config);

            if (agoraEngine != null) {
                agoraEngine.enableAudio();
                agoraEngine.setEnableSpeakerphone(true);
                agoraEngine.muteLocalAudioStream(true);
            }

        } catch (Exception e) {

            e.printStackTrace();

            Toast.makeText(this,
                    "Agora initialization failed",
                    Toast.LENGTH_LONG).show();
        }
    }
    private void speakTranslatedText(String text){

        if(textToSpeech != null){
            // Set to the language I will HEAR (the target translation language for me)
            textToSpeech.setLanguage(myTtsLocale);
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }

    }
    private void listenForCallEnd(String callId){

        db.collection("calls")
                .document(callId)
                .addSnapshotListener((value, error) -> {

                    if(value == null) return;

                    String status = value.getString("status");

                    if("ended".equals(status)){
                        showCallEndedDialog();
                    }
                });
    }
    private void showCallEndedDialog(){

        new AlertDialog.Builder(this)
                .setTitle("Call Ended")
                .setMessage("The other user has ended the call.")
                .setCancelable(false)
                .setPositiveButton("OK", (dialog, which) -> {

                    endCallLocally();

                })
                .show();
    }
    private boolean isRecognizerBusy = false; // add this field

    private void restartListening() {
        if (!isListening || isRecognizerBusy) return;

        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            try {
                isRecognizerBusy = true;
                speechRecognizer.cancel();

                // Wait after cancel before starting again
                new android.os.Handler(getMainLooper()).postDelayed(() -> {
                    try {
                        speechRecognizer.startListening(speechIntent);
                    } catch (Exception e) {
                        isRecognizerBusy = false;
                        e.printStackTrace();
                    }
                }, 300);

            } catch (Exception e) {
                isRecognizerBusy = false;
                e.printStackTrace();
            }
        }, 400);
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(agoraEngine != null){
            agoraEngine.leaveChannel();
            RtcEngine.destroy();
        }
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
        }

        if (translator != null) {
            translator.close();
        }
        if(textToSpeech != null){
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }
}