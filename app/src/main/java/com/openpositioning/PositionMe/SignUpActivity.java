package com.openpositioning.PositionMe;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SignUpActivity extends AppCompatActivity {

    private EditText usernameEditText, emailEditText, passwordEditText, mobileEditText;
    private Button signUpButton;
    private FirebaseAuth mAuth;
    private DatabaseReference databaseReference;
    private static final String SIGNUP_URL = "https://openpositioning.org/api/users/signup";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_up);

        // 绑定 UI 组件
        // Bind UI components
        usernameEditText = findViewById(R.id.username);
        emailEditText = findViewById(R.id.email);
        passwordEditText = findViewById(R.id.password);
        mobileEditText = findViewById(R.id.phone);
        signUpButton = findViewById(R.id.create);

        // 初始化 Firebase Authentication 和 Database
        // Initialize Firebase Authentication and Database
        mAuth = FirebaseAuth.getInstance();
        databaseReference = FirebaseDatabase.getInstance(
                "https://livelink-f37f6-default-rtdb.europe-west1.firebasedatabase.app"
        ).getReference("Users");

        // 注册按钮点击事件
        // Register button click event
        signUpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                registerUser();
            }
        });
    }

    private void registerUser() {
        String username = usernameEditText.getText().toString().trim();
        String email = emailEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();
        String mobile = mobileEditText.getText().toString().trim();

        if (TextUtils.isEmpty(username)) {
            usernameEditText.setError("Enter username!");
            return;
        }
        if (TextUtils.isEmpty(email)) {
            emailEditText.setError("Enter email!");
            return;
        }
        if (TextUtils.isEmpty(password)) {
            passwordEditText.setError("Enter password!");
            return;
        }
        if (password.length() < 6) {
            passwordEditText.setError("Password must be at least 6 characters!");
            return;
        }
        if (TextUtils.isEmpty(mobile)) {
            mobileEditText.setError("Enter mobile number!");
            return;
        }

        // **先在 Firebase 认证创建用户**
        // **Create a user in Firebase authentication first**
        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            // 发送 Email 验证邮件
                            //Send Email verification email
                            user.sendEmailVerification()
                                    .addOnCompleteListener(emailTask -> {
                                        if (emailTask.isSuccessful()) {
                                            Toast.makeText(SignUpActivity.this, "Verification email sent. Please verify before logging in.", Toast.LENGTH_LONG).show();
                                        } else {
                                            Toast.makeText(SignUpActivity.this, "Failed to send verification email!", Toast.LENGTH_SHORT).show();
                                        }
                                    });

                            // **Firebase 账号创建成功后，调用 `signUpUser()` 获取 `userKey`**
                            // **After the Firebase account is successfully created, call `signUpUser()` to get `userKey`**
                            signUpUser(user.getUid(), username, email, mobile, password);
                        }
                    } else {
                        if (task.getException() instanceof FirebaseAuthUserCollisionException) {
                            Toast.makeText(SignUpActivity.this, "Email already in use!", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(SignUpActivity.this, "Registration failed: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    /**
     * 发送 `signup` API 请求，并获取 `userKey`
     * Send a `signup` API request and get `userKey`
     */
    private void signUpUser(String userId, String username, String email, String mobile, String password) {
        OkHttpClient client = new OkHttpClient();

        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("username", username);
            jsonBody.put("email", email);
            jsonBody.put("password", password);
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        RequestBody body = RequestBody.create(jsonBody.toString(), JSON);

        Request request = new Request.Builder()
                .url(SIGNUP_URL)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                Log.e("SignUpActivity", "Signup failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    String errorBody = response.body().string();
                    Log.e("SignUpActivity", "Signup failed: " + response.code() + "\n" + errorBody);
                    return;
                }

                String responseBody = response.body().string();
                try {
                    JSONObject jsonResponse = new JSONObject(responseBody);
                    String userKey = jsonResponse.getString("api_key");  // 获取 userKey

                    // **保存 `userKey` 到 Firebase**
                    saveUserData(userId, username, email, mobile, userKey);

                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 存储完整用户信息 (username, email, mobile, userKey) 到 Firebase
     * Store complete user information (username, email, mobile, userKey) to Firebase
     */
    private void saveUserData(String userId, String username, String email, String mobile, String userKey) {
        if (userKey == null) {
            Log.e("SignUpActivity", "UserKey is null!");
            userKey = "9H8m_SU8K3xojnCD4iPobg";  // 默认值 default value
            return;
        }
        // 创建 User 对象
        // Create a User object
        User user = new User(username, email, mobile, userKey);

        // 存储数据到 Firebase
        // Store data in Firebase
        databaseReference.child(userId).setValue(user)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d("SignUpActivity", "User data and userKey saved to Firebase.");

                        // **存储成功后，再跳转 SignInActivity**
                        // **After successful storage, jump to SignInActivity**
                        Intent intent = new Intent(SignUpActivity.this, SignInActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();  // 关闭当前 Activity Close the current Activity
                    } else {
                        Log.e("SignUpActivity", "Failed to save user data: " + task.getException().getMessage());
                    }
                });
    }

    /**
     * 用户数据类
     */
    public static class User {
        public String username, email, mobile, userKey;

        public User() {
            // Default constructor required for Firebase
        }

        public User(String username, String email, String mobile, String userKey) {
            this.username = username;
            this.email = email;
            this.mobile = mobile;
            this.userKey = userKey;
        }
    }
}
