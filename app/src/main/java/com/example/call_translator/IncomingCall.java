package com.example.call_translator;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class IncomingCall extends AppCompatActivity {

    TextView tvCaller;
    Button btnAccept, btnReject;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incoming_call);

        tvCaller = findViewById(R.id.tvCaller);
        btnAccept = findViewById(R.id.btnAccept);
        btnReject = findViewById(R.id.btnReject);

        String caller = getIntent().getStringExtra("caller");

        tvCaller.setText("Incoming call from " + caller);

        btnAccept.setOnClickListener(v -> {

            Intent intent = new Intent(this, Call.class);
            startActivity(intent);
            finish();

        });

        btnReject.setOnClickListener(v -> finish());
    }
}