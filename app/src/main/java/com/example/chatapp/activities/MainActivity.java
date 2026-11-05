package com.example.chatapp.activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.chatapp.adapters.UsersAdapter;
import com.example.chatapp.crypto.CryptoEngine;
import com.example.chatapp.listeners.UserListener;
import com.example.chatapp.models.PreKeyBundle;
import com.example.chatapp.models.User;
import com.example.chatapp.databinding.ActivityMainBinding;
import com.example.chatapp.network.ApiClient;
import com.example.chatapp.network.ApiService;
import com.example.chatapp.utilities.Constants;
import com.example.chatapp.utilities.LocalDBManager;
import com.example.chatapp.utilities.PreferenceManager;
import com.example.chatapp.utilities.SocketHandler;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.Security;
import java.util.List;

import io.socket.client.Socket;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberPublicKeyParameters;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity implements UserListener {

    private ActivityMainBinding binding;
    private PreferenceManager preferenceManager;
    private Socket mSocket;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Security.removeProvider("BC");
        Security.insertProviderAt(new BouncyCastleProvider(), 1);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        preferenceManager = new PreferenceManager(getApplicationContext());

        loadUserDetails();
        initSocket();
        setupAndUploadKeys();

        // 🟢 Red Logout Button Setup
        binding.buttonSignOut.setOnClickListener(v -> signOut());

        // 🟢 NEW FEATURE: Click your profile to update picture
        binding.layoutProfilePic.setOnClickListener(v -> {
            pickProfileImage.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preferenceManager.getBoolean(Constants.KEY_IS_SIGNED_IN)) {
            getUsers();
            if (mSocket != null) {
                mSocket.off("receive_message");
                mSocket.on("receive_message", args -> runOnUiThread(this::getUsers)); // Triggers real-time bold update
            }
        }
    }

    // ==========================================
    // 🔥 NEW: UPDATE PROFILE PICTURE LOGIC
    // ==========================================
    private final ActivityResultLauncher<PickVisualMediaRequest> pickProfileImage = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(),
            uri -> {
                if (uri != null) {
                    try {
                        InputStream inputStream = getContentResolver().openInputStream(uri);
                        Bitmap bitmap = BitmapFactory.decodeStream(inputStream);

                        int width = 150;
                        int height = bitmap.getHeight() * width / bitmap.getWidth();
                        Bitmap smallBitmap = Bitmap.createScaledBitmap(bitmap, width, height, false);

                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        smallBitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos);
                        String encodedImage = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);

                        // Optimistic UI Update
                        binding.imageProfile.setImageBitmap(smallBitmap);

                        // Backend Request
                        ApiService apiService = ApiClient.getClient().create(ApiService.class);
                        ApiService.UpdateProfileRequest req = new ApiService.UpdateProfileRequest(
                                preferenceManager.getString(Constants.KEY_USER_ID),
                                encodedImage
                        );

                        apiService.updateProfile(req).enqueue(new Callback<ApiService.UpdateProfileResponse>() {
                            @Override
                            public void onResponse(Call<ApiService.UpdateProfileResponse> call, Response<ApiService.UpdateProfileResponse> response) {
                                if(response.isSuccessful() && response.body() != null && response.body().status) {
                                    preferenceManager.putString(Constants.KEY_IMAGE, encodedImage);
                                    Toast.makeText(MainActivity.this, "Profile picture updated", Toast.LENGTH_SHORT).show();
                                }
                            }
                            @Override
                            public void onFailure(Call<ApiService.UpdateProfileResponse> call, Throwable t) {
                                Toast.makeText(MainActivity.this, "Failed to update profile", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
    );

    // KEY UPLOADER (No Changes)
    private void setupAndUploadKeys() {
        if (preferenceManager.getString("my_identity_private") == null) {
            try {
                KeyPair myIdentityKey = CryptoEngine.generateX25519KeyPair();
                KeyPair mySignedPreKey = CryptoEngine.generateX25519KeyPair();
                AsymmetricCipherKeyPair pqKeyPair = CryptoEngine.generateKyberKeyPair();
                byte[] pqPrivBytes = ((KyberPrivateKeyParameters) pqKeyPair.getPrivate()).getEncoded();
                byte[] pqPubBytes = ((KyberPublicKeyParameters) pqKeyPair.getPublic()).getEncoded();

                preferenceManager.putString("my_identity_private", Base64.encodeToString(myIdentityKey.getPrivate().getEncoded(), Base64.NO_WRAP));
                preferenceManager.putString("my_signed_prekey_private", Base64.encodeToString(mySignedPreKey.getPrivate().getEncoded(), Base64.NO_WRAP));
                preferenceManager.putString("my_identity_public", Base64.encodeToString(myIdentityKey.getPublic().getEncoded(), Base64.NO_WRAP));
                preferenceManager.putString("my_signed_prekey_public", Base64.encodeToString(mySignedPreKey.getPublic().getEncoded(), Base64.NO_WRAP));
                preferenceManager.putString("my_pq_private", Base64.encodeToString(pqPrivBytes, Base64.NO_WRAP));
                preferenceManager.putString("my_pq_public", Base64.encodeToString(pqPubBytes, Base64.NO_WRAP));

                PreKeyBundle myBundle = new PreKeyBundle();
                myBundle.phone = preferenceManager.getString(Constants.KEY_PHONE);
                myBundle.identityPublic = preferenceManager.getString("my_identity_public");
                myBundle.signedPreKeyPublic = preferenceManager.getString("my_signed_prekey_public");
                myBundle.pqPreKeyPublic = preferenceManager.getString("my_pq_public");

                ApiClient.getClient().create(ApiService.class).uploadPreKeyBundle(myBundle).enqueue(new Callback<Void>() {
                    @Override public void onResponse(Call<Void> call, Response<Void> response) {}
                    @Override public void onFailure(Call<Void> call, Throwable t) {}
                });
            } catch (Exception e) {}
        }
    }

    private void getUsers() {
        loading(true);
        ApiService apiService = ApiClient.getClient().create(ApiService.class);
        String currentUserId = preferenceManager.getString(Constants.KEY_USER_ID);

        apiService.getUsers(currentUserId).enqueue(new Callback<List<User>>() {
            @Override
            public void onResponse(Call<List<User>> call, Response<List<User>> response) {
                loading(false);
                if (response.isSuccessful() && response.body() != null && response.body().size() > 0) {
                    LocalDBManager localDB = new LocalDBManager(MainActivity.this);
                    UsersAdapter usersAdapter = new UsersAdapter(response.body(), MainActivity.this, localDB, currentUserId);
                    binding.usersRecyclerView.setAdapter(usersAdapter);
                    binding.usersRecyclerView.setVisibility(View.VISIBLE);
                    binding.textErrorMessage.setVisibility(View.GONE);
                } else showErrorMessage();
            }
            @Override
            public void onFailure(Call<List<User>> call, Throwable t) { loading(false); showErrorMessage(); }
        });
    }

    private void showErrorMessage() {
        binding.textErrorMessage.setText("No users available");
        binding.textErrorMessage.setVisibility(View.VISIBLE);
        binding.usersRecyclerView.setVisibility(View.GONE);
    }

    private void loading(Boolean isLoading) { binding.progressBar.setVisibility(isLoading ? View.VISIBLE : View.INVISIBLE); }

    private void loadUserDetails() {
        binding.textName.setText(preferenceManager.getString(Constants.KEY_NAME));
        String base64Image = preferenceManager.getString(Constants.KEY_IMAGE);
        if (base64Image != null) {
            byte[] bytes = Base64.decode(base64Image, Base64.DEFAULT);
            binding.imageProfile.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
        }
    }

    private void initSocket() {
        SocketHandler.setSocket();
        mSocket = SocketHandler.getSocket();
        mSocket.on(Socket.EVENT_CONNECT, args -> runOnUiThread(() -> {
            try {
                String phone = preferenceManager.getString(Constants.KEY_PHONE);
                if (phone != null) mSocket.emit("register", new JSONObject().put("phone", phone));
            } catch (JSONException ignored) {}
        }));
        if (!mSocket.connected()) mSocket.connect();
    }

    private void signOut() {
        Toast.makeText(this, "Signing out...", Toast.LENGTH_SHORT).show();
        if (mSocket != null) { mSocket.disconnect(); mSocket.off(); }
        preferenceManager.clear();
        Intent intent = new Intent(getApplicationContext(), SignInActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    public void onUserClicked(User user) {
        startActivity(new Intent(getApplicationContext(), ChatActivity.class).putExtra(Constants.KEY_USER, user));
    }
}