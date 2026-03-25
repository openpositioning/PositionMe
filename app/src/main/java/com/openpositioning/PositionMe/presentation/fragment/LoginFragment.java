package com.openpositioning.PositionMe.presentation.fragment;

import static com.openpositioning.PositionMe.utils.UtilConstants.CREDENTIALS_KEY_EMAIL;
import static com.openpositioning.PositionMe.utils.UtilConstants.CREDENTIALS_KEY_PASSWORD;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.LoginManager;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.sensors.Observer;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * An extension of {@link Fragment} to manage signing in to OpenPosition and retrieving the user's
 * API key. Users can enter their credentials and (optionally) save them to their device.
 *
 * <p>If the user doesn't have an account, they can access the {@link RegisterFragment} to sign up
 * to OpenPosition's API.
 *
 * @see RegisterFragment
 * @see ServerCommunications
 * @see LoginManager
 */
public class LoginFragment extends Fragment implements Observer {
    private final String TAG = "LoginFragment";

    private Button loginButton;
    private TextView textRegisterHere;
    private EditText emailEditText;
    private EditText passwordEditText;
    private CheckBox checkboxSavePassword;
    private LoginManager loginManager;
    private ServerCommunications serverCommunications;

    public LoginFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_login, container, false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Initialise the UI elements, and the {@link LoginManager} for handling credentials
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        loginManager = LoginManager.getInstance();
        loginManager.initialise(this.getContext());
        serverCommunications = new ServerCommunications(this.getContext());
        serverCommunications.registerObserver(this);

        loginButton = view.findViewById(R.id.buttonLogin);
        checkboxSavePassword = view.findViewById(R.id.checkBoxSavePassword);

        textRegisterHere = view.findViewById(R.id.textLoginFragmentToRegister);
        emailEditText = view.findViewById(R.id.editTextLoginEmail);
        passwordEditText = view.findViewById(R.id.editTextLoginPassword);

        // Populate login screen with saved credentials, if present
        Map<String, String> savedCredentials = loginManager.getSavedLoginDetails();
        if (savedCredentials != null) {
            String email = savedCredentials.get(CREDENTIALS_KEY_EMAIL);
            String password = savedCredentials.get(CREDENTIALS_KEY_PASSWORD);
            emailEditText.setText(email);
            passwordEditText.setText(password);
            checkboxSavePassword.setChecked(true);
            Log.i(TAG, "Credentials loaded from device");
        }

        // Move to the registration screen
        textRegisterHere.setOnClickListener(
                v -> {
                    NavDirections action =
                            LoginFragmentDirections.actionLoginFragmentToRegisterFragment();
                    Navigation.findNavController(v).navigate(action);
                });

        loginButton.setOnClickListener(v -> attemptLogIn());
    }

    /**
     * Retrieve the user's input from the UI, and send a login request to the OpenPosition API.
     *
     * <p>If the user wishes to save their login details, the {@link LoginManager} will save these
     * on their device.
     */
    private void attemptLogIn() {
        String email = emailEditText.getText().toString();
        String password = passwordEditText.getText().toString();

        if (email.isEmpty() || password.isEmpty()) {
            new Handler(Looper.getMainLooper())
                    .post(
                            () -> {
                                Toast.makeText(
                                                this.getContext(),
                                                "Please fill all login fields and try again.",
                                                Toast.LENGTH_SHORT)
                                        .show();
                            });
            return;
        }

        if (checkboxSavePassword.isChecked()) {
            loginManager.saveLoginToDevice(email, password);
        }

        serverCommunications.logInUser(email, password);
    }

    /**
     * Saves the user's username and API key in the {@link LoginManager} for future reference, and
     * hands off to {@link MainActivity}.
     */
    private void finaliseLogin(String username, String key) {
        // Save for future reference
        loginManager.startLoginSession(username, key);

        new Handler(Looper.getMainLooper())
                .post(
                        () ->
                                Toast.makeText(
                                                this.getContext(),
                                                "Login Successful - Welcome " + username + "!",
                                                Toast.LENGTH_SHORT)
                                        .show());

        Intent intent = new Intent(requireContext(), MainActivity.class);
        startActivity(intent);
        requireActivity().finish();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Calls for the user's username and API key to be extracted from the server's response
     * before completing the log in process.
     */
    @Override
    public void update(Object[] objList) {
        boolean success = (boolean) objList[0];
        String infoString = objList[1].toString();
        if (!success) return;

        try {
            Map<String, String> userData = extractDataFromResponse(infoString);

            String username = userData.get("username");
            String key = userData.get("key");
            Log.i(TAG, "username: " + username);
            Log.i(TAG, "key: " + key);

            finaliseLogin(username, key);
        } catch (Exception e) {
            String message = "Bad response from server. Login halted";
            new Handler(Looper.getMainLooper())
                    .post(
                            () ->
                                    Toast.makeText(this.getContext(), message, Toast.LENGTH_SHORT)
                                            .show());
            Log.w(TAG, message);
        }
    }

    /**
     * Parses the server's response for the user's username and API key.
     *
     * @return The user's username and API key in a Map.
     * @throws JSONException If the response parsing fails.
     */
    private Map<String, String> extractDataFromResponse(String response) throws JSONException {
        Map<String, String> userData = new HashMap<>();
        try {
            JSONObject jsonObject = new JSONObject(response);
            userData.put("username", String.valueOf(jsonObject.get("username")));
            userData.put("key", String.valueOf(jsonObject.get("api_key")));
            return userData;
        } catch (JSONException e) {
            Log.w(TAG, "Error parsing server response: " + e.getCause());
            throw e;
        }
    }
}
