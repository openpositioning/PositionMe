package com.openpositioning.PositionMe;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;

public class Reset extends AppCompatActivity {

    private EditText emailEditText;
    private Button resetButton;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reset);

        // Bind UI components
        emailEditText = findViewById(R.id.email);
        resetButton = findViewById(R.id.send);

        // Initialize Firebase Auth
        mAuth = FirebaseAuth.getInstance();

        // Set listener for the "Send" button
        resetButton.setOnClickListener(v -> checkEmailBeforeReset());
    }

    /**
     * Checks if the email is registered before sending a password reset email.
     * Uses a dummy login attempt to determine if the email is valid.
     */
    private void checkEmailBeforeReset() {
        String email = emailEditText.getText().toString().trim();

        if (TextUtils.isEmpty(email)) {
            emailEditText.setError("Enter your email!");
            return;
        }

        // Attempt to log in with a dummy password to check if the account exists
        mAuth.signInWithEmailAndPassword(email, "dummyPassword123")
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        // Email exists but password is wrong — proceed with reset
                        sendResetEmail(email);
                    } else {
                        // Handle different types of errors
                        if (task.getException() instanceof FirebaseAuthInvalidUserException) {
                            Toast.makeText(Reset.this, "This email is not registered!", Toast.LENGTH_LONG).show();
                        } else if (task.getException() instanceof FirebaseAuthInvalidCredentialsException) {
                            // Email exists (invalid password) — proceed with reset
                            sendResetEmail(email);
                        } else {
                            Toast.makeText(Reset.this, "Error: " + task.getException().getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    /**
     * Sends a password reset email to the specified address.
     */
    private void sendResetEmail(String email) {
        mAuth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Toast.makeText(Reset.this, "Password reset email sent! Check your inbox.", Toast.LENGTH_LONG).show();
                        finish(); // Close the activity after successful send
                    } else {
                        Toast.makeText(Reset.this, "Failed to send reset email!", Toast.LENGTH_SHORT).show();
                    }
                });
    }
}


