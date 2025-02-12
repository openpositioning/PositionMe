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

        // 绑定 UI 组件
        emailEditText = findViewById(R.id.email);
        resetButton = findViewById(R.id.send);

        // 初始化 Firebase Auth
        mAuth = FirebaseAuth.getInstance();

        // 监听 "Send" 按钮点击
        resetButton.setOnClickListener(v -> checkEmailBeforeReset());
    }

    private void checkEmailBeforeReset() {
        String email = emailEditText.getText().toString().trim();

        if (TextUtils.isEmpty(email)) {
            emailEditText.setError("Enter your email!");
            return;
        }

        // 尝试使用错误密码登录该 Email，以检查是否已注册
        mAuth.signInWithEmailAndPassword(email, "dummyPassword123")
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        // 账号存在，但密码错误，执行重置密码
                        sendResetEmail(email);
                    } else {
                        // 检查错误类型
                        if (task.getException() instanceof FirebaseAuthInvalidUserException) {
                            Toast.makeText(Reset.this, "This email is not registered!", Toast.LENGTH_LONG).show();
                        } else if (task.getException() instanceof FirebaseAuthInvalidCredentialsException) {
                            // 说明 Email 存在，只是密码错误，可以发送密码重置邮件
                            sendResetEmail(email);
                        } else {
                            Toast.makeText(Reset.this, "Error: " + task.getException().getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private void sendResetEmail(String email) {
        mAuth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Toast.makeText(Reset.this, "Password reset email sent! Check your inbox.", Toast.LENGTH_LONG).show();
                        finish(); // 关闭当前 Activity
                    } else {
                        Toast.makeText(Reset.this, "Failed to send reset email!", Toast.LENGTH_SHORT).show();
                    }
                });
    }
}

