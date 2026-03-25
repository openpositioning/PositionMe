package com.openpositioning.PositionMe.data.remote;

import static com.openpositioning.PositionMe.utils.UtilConstants.CREDENTIALS_FILE_NAME;
import static com.openpositioning.PositionMe.utils.UtilConstants.CREDENTIALS_KEY_EMAIL;
import static com.openpositioning.PositionMe.utils.UtilConstants.CREDENTIALS_KEY_PASSWORD;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.util.Map;

/**
 * The LoginManager class handles the user's identity, both when signing in (email and password) and
 * when other classes call upon the user's information as returned from the server (username and API
 * key)
 *
 * <p>The user's login credentials are securely stored on their device.
 *
 * <p>Implements the Singleton design pattern such that only one instance of the LoginManager can
 * exist. This allows the user's details to be stored in one object and accessed by any other class.
 *
 * @see EncryptedSharedPreferences
 * @see ServerCommunications
 */
public class LoginManager {
    private final String TAG = "LoginManager";

    private boolean isLoggedIn;

    private String username;
    private String userKey;

    // Secure persistent storage of credentials (save password box)
    SharedPreferences credentialStore;

    // Singleton Class
    private static final LoginManager loginManager = new LoginManager();

    private LoginManager() {
        this.isLoggedIn = false;
    }

    public static LoginManager getInstance() {
        return loginManager;
    }

    /** Run once on startup to initialise the credential store */
    public void initialise(Context context) {
        if (credentialStore == null) {
            try {
                MasterKey masterKey =
                        new MasterKey.Builder(context)
                                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                                .build();
                credentialStore =
                        EncryptedSharedPreferences.create(
                                context,
                                CREDENTIALS_FILE_NAME,
                                masterKey,
                                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
                Log.d(TAG, "Credential storage initialised");
            } catch (Exception e) {
                Log.w(TAG, "Error initialising the login credentials store: " + e.getMessage());
            }
        }
    }

    /**
     * Saves user's name and API key to this instance of the {@link LoginManager}. These details
     * will be cleared upon {@link LoginManager#endLoginSession() logging out} or closing the app.
     *
     * @param username The user's username, for UI elements
     * @param userKey The user's API key, for network requests
     */
    public void startLoginSession(String username, String userKey) {
        this.username = username;
        this.userKey = userKey;
        this.isLoggedIn = true;
        Log.d(
                TAG,
                "Login session for \""
                        + username
                        + "\" started. API key will be used for future requests");
    }

    /** Clears the user's details from app session storage */
    public void endLoginSession() {
        this.username = null;
        this.userKey = null;
        this.isLoggedIn = false;
        Log.d(TAG, "Login session ended. User will need to log in again");
    }

    /**
     * Save the provided credentials to the persistent storage.
     *
     * <p>Unlike {@link LoginManager#startLoginSession(String, String) startLoginSession()}, these
     * credentials are saved to disk and can be retrieved between app launches.
     *
     * @param email The user's email address
     * @param password The user's password
     */
    public void saveLoginToDevice(String email, String password) {
        credentialStore
                .edit()
                .putString(CREDENTIALS_KEY_EMAIL, email)
                .putString(CREDENTIALS_KEY_PASSWORD, password)
                .apply();
        Log.i(TAG, "Credentials successfully saved on device");
    }

    /**
     * Retrieves the user's credentials, if they exist on the device
     *
     * @return The user's email and password
     */
    public Map<String, String> getSavedLoginDetails() {
        if (credentialStore.contains(CREDENTIALS_KEY_EMAIL)
                && credentialStore.contains(CREDENTIALS_KEY_PASSWORD)) {
            Log.i(TAG, "Credentials found");
            return (Map<String, String>) credentialStore.getAll();
        } else {
            Log.d(TAG, "No saved credentials found");
            return null;
        }
    }

    public boolean checkLoginStatus() {
        return isLoggedIn;
    }

    public String getUsername() {
        return username;
    }

    public String getUserKey() {
        return userKey;
    }
}
