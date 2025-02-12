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
    // 声明 ServerCommunications 对象
    private ServerCommunications serverCommunications;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_in);

        // 绑定 UI 组件
        // Bind UI components
        emailEditText = findViewById(R.id.email);
        passwordEditText = findViewById(R.id.password);
        signInButton = findViewById(R.id.sign_in);
        TextView signupText = findViewById(R.id.signup_text);
        TextView forgotPasswordText = findViewById(R.id.forgot_password);

        // 初始化 Firebase Auth
        // Initialize Firebase Auth
        mAuth = FirebaseAuth.getInstance();

        // 登录按钮点击事件
        // Login button click event
        signInButton.setOnClickListener(v -> loginUser());

        // 设置 Sign Up 可点击跳转
        // Set Sign Up to jump to clickable
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
                ds.setColor(Color.BLUE); // 点击后颜色 Click Color
                ds.setUnderlineText(true); // 添加下划线 Add underline
            }
        };
        spannableString.setSpan(clickableSpan, text.indexOf("Sign Up"), text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        signupText.setText(spannableString);
        signupText.setMovementMethod(LinkMovementMethod.getInstance()); // 让文本可点击 Make text clickable

        // 设置 "Forgot your password?" 可点击跳转 Set "Forgot your password?" to jump to the next page
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
        // 获取输入的邮箱和密码
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

        // 调用 Firebase API 登录
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            if (user.isEmailVerified()) {
                                // 登录成功后创建 ServerCommunications 实例
                                serverCommunications = new ServerCommunications(SignInActivity.this);
                                // 设置回调，等待 Firebase 获取 userKey 并初始化 URL 完成
                                serverCommunications.setURLInitializedListener(new ServerCommunications.URLInitializedListener() {
                                    @Override
                                    public void onURLInitialized() {
                                        // 当 userKey 获取并初始化完成后，回调被触发
                                        runOnUiThread(() -> {
                                            Toast.makeText(SignInActivity.this, "Login Successful!", Toast.LENGTH_SHORT).show();
                                            Intent intent = new Intent(SignInActivity.this, MainActivity.class);
                                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                            startActivity(intent);
                                            finish();
                                        });
                                    }
                                });
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
