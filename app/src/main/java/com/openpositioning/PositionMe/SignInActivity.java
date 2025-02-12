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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_in);

        // 绑定 UI 组件
        emailEditText = findViewById(R.id.email);
        passwordEditText = findViewById(R.id.password);
        signInButton = findViewById(R.id.sign_in);
        TextView signupText = findViewById(R.id.signup_text);
        TextView forgotPasswordText = findViewById(R.id.forgot_password);

        // 初始化 Firebase Auth
        mAuth = FirebaseAuth.getInstance();

        // 登录按钮点击事件
        signInButton.setOnClickListener(v -> loginUser());

        // 设置 Sign Up 可点击跳转
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
                ds.setColor(Color.BLUE); // 点击后颜色
                ds.setUnderlineText(true); // 添加下划线
            }
        };
        spannableString.setSpan(clickableSpan, text.indexOf("Sign Up"), text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        signupText.setText(spannableString);
        signupText.setMovementMethod(LinkMovementMethod.getInstance()); // 让文本可点击

        // 设置 "Forgot your password?" 可点击跳转
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
                ds.setColor(Color.BLUE);
                ds.setUnderlineText(true);
            }
        };
        forgotSpannable.setSpan(forgotClickableSpan, 0, forgotText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        forgotPasswordText.setText(forgotSpannable);
        forgotPasswordText.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private void loginUser() {
        String email = emailEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();

        // 检查输入是否为空
        if (TextUtils.isEmpty(email)) {
            emailEditText.setError("Enter email!");
            return;
        }
        if (TextUtils.isEmpty(password)) {
            passwordEditText.setError("Enter password!");
            return;
        }

        // Firebase 登录
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            if (user.isEmailVerified()) {
                                // 登录成功，跳转到 MainActivity
                                Toast.makeText(SignInActivity.this, "Login Successful!", Toast.LENGTH_SHORT).show();
                                Intent intent = new Intent(SignInActivity.this, MainActivity.class);
                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finish();
                            } else {
                                Toast.makeText(SignInActivity.this, "Please verify your email before logging in.", Toast.LENGTH_LONG).show();
                            }
                        }
                    } else {
                        Toast.makeText(SignInActivity.this, "Login failed: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
    }
}
