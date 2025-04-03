package com.openpositioning.PositionMe;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class SignInActivity extends AppCompatActivity {

    private EditText emailEditText, passwordEditText;
    private Button signInButton;
    private FirebaseAuth mAuth;
    private ServerCommunications serverCommunications;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_in);

        // Bind UI components
        emailEditText = findViewById(R.id.email);
        passwordEditText = findViewById(R.id.password);
        signInButton = findViewById(R.id.sign_in);
        TextView signupText = findViewById(R.id.signup_text);
        TextView forgotPasswordText = findViewById(R.id.forgot_password);

        // Initialize Firebase Authentication instance
        mAuth = FirebaseAuth.getInstance();

        // Set click listener for the Sign In button
        signInButton.setOnClickListener(v -> loginUser());

        // Make "Sign Up" text clickable to navigate to the SignUpActivity
        String text = "Don't have an account? Sign Up";
        SpannableString spannableString = new SpannableString(text);
        ClickableSpan clickableSpan = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                Intent intent = new Intent(SignInActivity.this, SignUpActivity.class);
                startActivity(intent);
            }

            @Override
            public void updateDrawState(@NonNull TextPaint ds) {
                super.updateDrawState(ds);
                ds.setColor(Color.BLUE);         // Set click color
                ds.setUnderlineText(true);       // Underline the clickable text
            }
        };
        spannableString.setSpan(clickableSpan, text.indexOf("Sign Up"), text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        signupText.setText(spannableString);
        signupText.setMovementMethod(LinkMovementMethod.getInstance()); // Enable link-style text interaction

        // Make "Forgot your password?" text clickable to navigate to the Reset activity
        String forgotText = "Forgot your password?";
        SpannableString forgotSpannable = new SpannableString(forgotText);
        ClickableSpan forgotClickableSpan = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                Intent intent = new Intent(SignInActivity.this, Reset.class);
                startActivity(intent);
            }

            @Override
            public void updateDrawState(@NonNull TextPaint ds) {
                super.updateDrawState(ds);
                ds.setColor(Color.BLUE);         // Set click color
                ds.setUnderlineText(true);       // Underline the clickable text
            }
        };
        forgotSpannable.setSpan(forgotClickableSpan, 0, forgotText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        forgotPasswordText.setText(forgotSpannable);
        forgotPasswordText.setMovementMethod(LinkMovementMethod.getInstance()); // Enable clickable behavior
    }


    /**
     * Attempts to log in the user using Firebase Authentication.
     * Validates input fields, handles Firebase login, email verification,
     * and initializes server communications upon successful login.
     */
    private void loginUser() {
        // Retrieve the entered email and password
        String email = emailEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();

        // Validate that email is not empty
        if (TextUtils.isEmpty(email)) {
            emailEditText.setError("Enter email!");
            return;
        }

        // Validate that password is not empty
        if (TextUtils.isEmpty(password)) {
            passwordEditText.setError("Enter password!");
            return;
        }

        // Attempt to log in using Firebase Authentication
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            // Check if the user's email is verified
                            if (user.isEmailVerified()) {
                                // Create an instance of ServerCommunications after successful login
                                serverCommunications = new ServerCommunications(SignInActivity.this);
                                // Set a callback listener for when userKey and server URL are initialized
                                serverCommunications.setURLInitializedListener(new ServerCommunications.URLInitializedListener() {
                                    @Override
                                    public void onURLInitialized() {
                                        // This is called after the userKey has been retrieved and URL is ready
                                        runOnUiThread(() -> {
                                            Toast.makeText(SignInActivity.this, "Login Successful!", Toast.LENGTH_SHORT).show();

                                            // Navigate to the main activity and clear login screen from the back stack
                                            Intent intent = new Intent(SignInActivity.this, MainActivity.class);
                                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                            startActivity(intent);
                                            finish();
                                        });
                                    }
                                });
                            } else {
                                // Notify user to verify their email first
                                Toast.makeText(SignInActivity.this, "Please verify your email before logging in.", Toast.LENGTH_LONG).show();
                            }
                        }
                    } else {
                        // Show error message if login fails
                        Toast.makeText(SignInActivity.this, "Login failed: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
    }

}
