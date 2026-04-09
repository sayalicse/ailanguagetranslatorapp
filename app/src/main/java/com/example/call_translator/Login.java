package com.example.call_translator;

import android.graphics.Color;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;



import android.content.Intent;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.HashMap;
import java.util.Map;

public class Login extends AppCompatActivity {

    EditText etEmail, etPassword;
    Button btnLogin,btnregister;
    TextView tvRegister;


    FirebaseAuth auth;
    FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
         tvRegister = findViewById(R.id.tvRegister);
        String text = "Don't have an account? Register";
        SpannableString spannable = new SpannableString(text);

        spannable.setSpan(
                new ForegroundColorSpan(Color.parseColor("#03DAC6")),
                23, text.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        tvRegister.setText(spannable);

        tvRegister.setOnClickListener(v -> {
            startActivity(new Intent(Login.this, Register.class));
        });

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        btnLogin.setOnClickListener(v -> loginUser());

    }

    private void loginUser(){

        String email = etEmail.getText().toString();
        String password = etPassword.getText().toString();

        auth.signInWithEmailAndPassword(email,password)
                .addOnCompleteListener(task -> {

                    if(task.isSuccessful()){

                        String uid = auth.getCurrentUser().getUid();

                        FirebaseMessaging.getInstance().getToken()
                                .addOnSuccessListener(token -> {

                                    Map<String,Object> update = new HashMap<>();
                                    update.put("online", true);
                                    update.put("lastSeen", System.currentTimeMillis());
                                    update.put("fcmToken", token);

                                    db.collection("users")
                                            .document(uid)
                                            .update(update);
                                });

                        Toast.makeText(this,"Login Successful",Toast.LENGTH_SHORT).show();

                        startActivity(new Intent(Login.this, Call.class));
                        finish();

                    }else{

                        Toast.makeText(this,"Login Failed",Toast.LENGTH_SHORT).show();

                    }

                });

    }
}