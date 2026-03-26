package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.sensors.Observer;

public class RegisterFragment extends Fragment implements Observer {
    private final String TAG = "RegisterFragment";
    private Button registerButton;
    private TextView textLogInHere;

    private EditText usernameEditText;
    private EditText emailEditText;
    private EditText passwordEditText;
    private EditText passwordCheckEditText;
    private ServerCommunications serverCommunications;

    public RegisterFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_register, container, false);
    }

    /** Initialise UI elements and set onClick actions for the buttons. */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        serverCommunications = new ServerCommunications(this.getContext());
        serverCommunications.registerObserver(this);

        registerButton = view.findViewById(R.id.buttonRegister);

        textLogInHere = view.findViewById(R.id.textRegisterFragmentToLogin);
        usernameEditText = view.findViewById(R.id.editTextRegisterUsername);
        emailEditText = view.findViewById(R.id.editTextRegisterEmail);
        passwordEditText = view.findViewById(R.id.editTextRegisterPassword);
        passwordCheckEditText = view.findViewById(R.id.editTextRegisterPasswordAgain);

        textLogInHere.setOnClickListener(
                v -> {
                    NavDirections action =
                            LoginFragmentDirections.actionRegisterFragmentToLoginFragment();
                    Navigation.findNavController(v).navigate(action);
                });

        registerButton.setOnClickListener(v -> attemptRegistration());
    }

    private void attemptRegistration() {
        String username = usernameEditText.getText().toString();
        String email = emailEditText.getText().toString();
        String password = passwordEditText.getText().toString();
        String passwordCheck = passwordCheckEditText.getText().toString();

        if (username.isEmpty()
                || email.isEmpty()
                || password.isEmpty()
                || passwordCheck.isEmpty()) {
            new Handler(Looper.getMainLooper())
                    .post(
                            () -> {
                                Toast.makeText(
                                                this.getContext(),
                                                "Please fill all login fields, and try again.",
                                                Toast.LENGTH_SHORT)
                                        .show();
                            });
            return;
        } else if (!password.equals(passwordCheck)) {
            new Handler(Looper.getMainLooper())
                    .post(
                            () -> {
                                Toast.makeText(
                                                this.getContext(),
                                                "Please check your passwords are the same, and try"
                                                        + " again.",
                                                Toast.LENGTH_SHORT)
                                        .show();
                            });
            passwordEditText.setText("");
            passwordCheckEditText.setText("");
            return;
        }

        // Disable UI to prevent multiple registration attempts
        enableUIElements(false);

        serverCommunications.registerUserDetails(username, email, password);
    }

    /**
     * Enable or disable all registration UI elements to prevent users from modifying data while the
     * server and/or app is processing it
     *
     * <p>This must be called from the main thread.
     *
     * @param state True to enable UI elements; False to disable
     */
    private void enableUIElements(boolean state) {
        registerButton.setEnabled(state);
        usernameEditText.setEnabled(state);
        emailEditText.setEnabled(state);
        passwordEditText.setEnabled(state);
        passwordCheckEditText.setEnabled(state);
        textLogInHere.setClickable(state);
    }

    @Override
    public void update(Object[] objList) {
        boolean success = (boolean) objList[0];
        if (!success) {
            new Handler(Looper.getMainLooper()).post(() -> enableUIElements(true));
            return;
        }

        try {
            new Handler(Looper.getMainLooper())
                    .post(
                            () -> {
                                Toast.makeText(
                                                this.getContext(),
                                                "Registration Successful!\nPlease now sign in",
                                                Toast.LENGTH_SHORT)
                                        .show();
                            });

            NavDirections action = LoginFragmentDirections.actionRegisterFragmentToLoginFragment();
            Navigation.findNavController(this.getView()).navigate(action);
        } catch (IllegalStateException e) {
            Log.w(TAG, e.getMessage());
        } catch (Exception e) {
            String message = "Bad response from server. Registration halted";
            new Handler(Looper.getMainLooper())
                    .post(
                            () -> {
                                Toast.makeText(this.getContext(), message, Toast.LENGTH_SHORT)
                                        .show();
                            });
            Log.w(TAG, message);
        }
    }
}
