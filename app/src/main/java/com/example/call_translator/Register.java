package com.example.call_translator;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class Register extends AppCompatActivity {

    EditText etName, etEmail, etPassword;
    Button btnRegister,btnLogin;
    TextView tvLogin;

    FirebaseAuth auth;
    FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        etName = findViewById(R.id.etName);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnRegister = findViewById(R.id.btnRegister);
        tvLogin = findViewById(R.id.tvLogin);

        String text = "Already have an account? Login";
        SpannableString spannable = new SpannableString(text);

        spannable.setSpan(
                new ForegroundColorSpan(Color.parseColor("#03DAC6")),
                25, text.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        tvLogin.setText(spannable);


        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        btnRegister.setOnClickListener(v -> registerUser());
        tvLogin.setOnClickListener(v -> {
            startActivity(new Intent(Register.this, Login.class));
        });
    }

    private void registerUser() {

        String name = etName.getText().toString();
        String email = etEmail.getText().toString();
        String password = etPassword.getText().toString();

        auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {

                    if(task.isSuccessful()){

                        String uid = auth.getCurrentUser().getUid();

                        Map<String, Object> user = new HashMap<>();
                        user.put("name", name);
                        user.put("email", email);
                        user.put("online", true);
                        user.put("lastSeen", System.currentTimeMillis());
                        user.put("fcmToken", "");

                        db.collection("users")
                                .document(uid)
                                .set(user)
                                .addOnSuccessListener(unused ->
                                        Toast.makeText(this,"Registered Successfully",Toast.LENGTH_SHORT).show());
                        Intent intent = new Intent(this, Login.class);
                        startActivity(intent);
                        finish();


                    } else {

                        Toast.makeText(this,"Registration Failed",Toast.LENGTH_SHORT).show();

                    }

                });
    }
}