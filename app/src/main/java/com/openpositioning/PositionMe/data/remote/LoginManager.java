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

    private String username;
    private String userKey;

    // Secure persistent storage of credentials (save password box)
    SharedPreferences credentialStore;

    // Singleton Class
    private static final LoginManager loginManager = new LoginManager();

    private LoginManager() {}

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
     * Save the provided credentials to the persistent storage
     *
     * @param email The user's email address
     * @param password The user's password
     */
    public void saveLoginDetails(String email, String password) {
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

    public void setUsername(String username) {
        this.username = username;
    }

    public void setUserKey(String key) {
        userKey = key;
    }

    public String getUsername() {
        return username;
    }

    public String getUserKey() {
        return userKey;
    }
}
