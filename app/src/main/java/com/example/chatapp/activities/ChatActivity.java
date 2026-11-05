package com.example.chatapp.activities;

import android.app.Dialog;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.chatapp.R;
import com.example.chatapp.adapters.ChatAdapter;
import com.example.chatapp.crypto.DoubleRatchet;
import com.example.chatapp.crypto.X3DHManager;
import com.example.chatapp.databinding.ActivityChatBinding;
import com.example.chatapp.models.ChatMessage;
import com.example.chatapp.models.PreKeyBundle;
import com.example.chatapp.models.RatchetHeader;
import com.example.chatapp.models.User;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.socket.client.Socket;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ChatActivity extends AppCompatActivity {
    private ActivityChatBinding binding;
    private User receiverUser;
    private List<ChatMessage> chatMessages;
    private ChatAdapter chatAdapter;
    private PreferenceManager preferenceManager;
    private Socket mSocket;
    private LocalDBManager localDB;
    private DoubleRatchet sessionRatchet;
    private X3DHManager.X3DHResult activeX3DH;
    private String myIdentityPublicBase64;
    private PreKeyBundle fetchedPeerBundle;
    private final Set<String> processedCiphertexts = new HashSet<>();
    private static final String TAG = "CRYPTO_PROTOCOL";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        loadReceiverDetails();
        if (receiverUser != null) {
            init();
        }
    }

    private void init() {
        preferenceManager = new PreferenceManager(getApplicationContext());
        localDB = new LocalDBManager(this);
        loadSessionState();

        chatMessages = new ArrayList<>();
        chatAdapter = new ChatAdapter(
                chatMessages,
                preferenceManager.getString(Constants.KEY_USER_ID),
                getBitmapFromEncodedString(receiverUser.image),
                this::showImageFullScreenDialog // Passes the image click to the new Dialog function
        );
        binding.chatRecyclerView.setAdapter(chatAdapter);
        mSocket = SocketHandler.getSocket();

        listenMessages();
        loadChatHistory();
        initializeCryptoSession();

        if (!mSocket.connected()) mSocket.connect();
        fetchPendingMessages();
        mSocket.on(Socket.EVENT_CONNECT, args -> runOnUiThread(this::fetchPendingMessages));

        binding.layoutSend.setOnClickListener(v -> {
            String text = binding.inputMessage.getText().toString().trim();
            if (!text.isEmpty()) {
                binding.inputMessage.setText("");
                sendEncryptedPayload(text, "text");
            }
        });

        binding.layoutAttachment.setOnClickListener(v -> pickImage.launch("image/*"));
        binding.imageBack.setOnClickListener(v -> finish());

        // NEW FEATURE: User Profile Viewer on 'i' icon click
        binding.imageInfo.setOnClickListener(v -> showUserProfileDialog());
    }

    // ==========================================
    // 🔥 NEW: SHOW USER PROFILE DIALOG
    // ==========================================
    private void showUserProfileDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_user_profile);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        ImageView imageProfile = dialog.findViewById(R.id.dialogImageProfile);
        TextView textName = dialog.findViewById(R.id.dialogTextName);
        TextView textPhone = dialog.findViewById(R.id.dialogTextPhone);

        Bitmap bmp = getBitmapFromEncodedString(receiverUser.image);
        if (bmp != null) imageProfile.setImageBitmap(bmp);
        textName.setText(receiverUser.name);
        textPhone.setText(receiverUser.phone);

        dialog.findViewById(R.id.buttonCloseDialog).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    // ==========================================
    // 🔥 NEW: SHOW FULL SCREEN IMAGE DIALOG
    // ==========================================
    private void showImageFullScreenDialog(Bitmap bitmap) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_image_viewer);

        ImageView expandedImage = dialog.findViewById(R.id.imageExpanded);
        expandedImage.setImageBitmap(bitmap);

        dialog.findViewById(R.id.buttonClose).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.buttonDownload).setOnClickListener(v -> downloadImageToGallery(bitmap));

        dialog.show();
    }

    // ==========================================
    // 🔥 NEW: DOWNLOAD IMAGE TO GALLERY
    // ==========================================
    private void downloadImageToGallery(Bitmap bitmap) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "ChatApp_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }

            Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = getContentResolver().insert(collection, values);

            if (item != null) {
                try (OutputStream out = getContentResolver().openOutputStream(item)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(item, values, null, null);
                }
                showToast("Image Downloaded to Gallery!");
            }
        } catch (Exception e) {
            e.printStackTrace();
            showToast("Failed to download image");
        }
    }

    // KEEPING YOUR EXISTING ENCRYPTION AND SOCKET LOGIC INTACT BELOW:
    private void fetchPendingMessages() {
        try {
            JSONObject data = new JSONObject();
            data.put("phone", preferenceManager.getString(Constants.KEY_PHONE));
            data.put("peerPhone", receiverUser.phone);
            mSocket.emit("fetch_pending", data);
        } catch (JSONException e) {
            Log.e(TAG, "Error requesting pending messages", e);
        }
    }

    private final ActivityResultLauncher<String> pickImage =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    showToast("Compressing image, please wait...");
                    new Thread(() -> {
                        try {
                            String base64Media = compressAndEncodeImage(uri);
                            runOnUiThread(() -> {
                                if (base64Media != null) sendEncryptedPayload(base64Media, "image");
                                else showToast("Failed to process image.");
                            });
                        } catch (Throwable t) {
                            runOnUiThread(() -> showToast("Error processing media."));
                        }
                    }).start();
                }
            });

    private String compressAndEncodeImage(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(inputStream, null, options);
            if (inputStream != null) inputStream.close();

            int reqWidth = 400; int reqHeight = 400;
            int inSampleSize = 1;
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                final int halfHeight = options.outHeight / 2;
                final int halfWidth = options.outWidth / 2;
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2;
                }
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = inSampleSize;
            InputStream inputStream2 = getContentResolver().openInputStream(uri);
            Bitmap decodedBitmap = BitmapFactory.decodeStream(inputStream2, null, options);
            if (inputStream2 != null) inputStream2.close();
            if (decodedBitmap == null) return null;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            decodedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos);
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private void sendEncryptedPayload(String content, String type) {
        if (sessionRatchet == null) {
            if (fetchedPeerBundle == null) {
                showToast("Still fetching secure keys... please wait.");
                return;
            }
            try {
                String myName = preferenceManager.getString(Constants.KEY_NAME);
                myIdentityPublicBase64 = preferenceManager.getString("my_identity_public");
                byte[] myIdPrivBytes = Base64.decode(preferenceManager.getString("my_identity_private"), Base64.NO_WRAP);
                byte[] myIdPubBytes = Base64.decode(myIdentityPublicBase64, Base64.NO_WRAP);

                KeyFactory kf = KeyFactory.getInstance("X25519", "BC");
                KeyPair myIdentityKey = new KeyPair(kf.generatePublic(new X509EncodedKeySpec(myIdPubBytes)), kf.generatePrivate(new PKCS8EncodedKeySpec(myIdPrivBytes)));
                activeX3DH = X3DHManager.initiateHandshake(fetchedPeerBundle, myIdentityKey, myName, receiverUser.name);
                sessionRatchet = activeX3DH.ratchet;
            } catch (Exception e) {
                showToast("Crypto Setup Failed: " + e.getMessage());
                return;
            }
        }
        try {
            JSONObject plaintextJson = new JSONObject();
            plaintextJson.put("type", type);
            plaintextJson.put("data", content);

            RatchetHeader header = sessionRatchet.encrypt(plaintextJson.toString().getBytes(StandardCharsets.UTF_8));
            saveSessionState();

            JSONObject message = new JSONObject();
            message.put("sender", preferenceManager.getString(Constants.KEY_PHONE));
            message.put("receiver", receiverUser.phone);
            message.put("ciphertext", Base64.encodeToString(header.ciphertext, Base64.NO_WRAP));
            message.put("dhPub", Base64.encodeToString(header.dhPub, Base64.NO_WRAP));
            message.put("pn", header.pn);
            message.put("n", header.n);

            if (activeX3DH != null) {
                message.put("x3dh_ek", activeX3DH.ephemeralPublicBase64);
                message.put("x3dh_ik", myIdentityPublicBase64);
                message.put("pq_ct", activeX3DH.pqCiphertextBase64);
            }

            mSocket.emit("send_message", message);
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.senderId = preferenceManager.getString(Constants.KEY_USER_ID);
            chatMessage.message = content; chatMessage.type = type;
            chatMessage.dateTime = getReadableDateTime(new Date());

            try { localDB.saveMessage(chatMessage.senderId, receiverUser.id, content, type, chatMessage.dateTime); } catch (Exception ignored) {}

            chatMessages.add(chatMessage);
            chatAdapter.notifyItemInserted(chatMessages.size() - 1);
            binding.chatRecyclerView.smoothScrollToPosition(chatMessages.size() - 1);
            activeX3DH = null;
        } catch (Exception e) { showToast("Failed to encrypt message."); }
    }

    private void listenMessages() {
        mSocket.off("receive_message");
        mSocket.on("receive_message", args -> runOnUiThread(() -> {
            JSONObject data = (JSONObject) args[0];
            try {
                String sender = data.getString("sender");
                if (sender.equals(receiverUser.phone)) {
                    String ciphertextStr = data.getString("ciphertext");
                    if (processedCiphertexts.contains(ciphertextStr)) return;
                    processedCiphertexts.add(ciphertextStr);

                    if (sessionRatchet == null && data.has("x3dh_ek") && data.has("x3dh_ik") && data.has("pq_ct")) {
                        try {
                            byte[] myIdPrivBytes = Base64.decode(preferenceManager.getString("my_identity_private"), Base64.NO_WRAP);
                            byte[] myIdPubBytes = Base64.decode(preferenceManager.getString("my_identity_public"), Base64.NO_WRAP);
                            byte[] mySpkPrivBytes = Base64.decode(preferenceManager.getString("my_signed_prekey_private"), Base64.NO_WRAP);
                            byte[] mySpkPubBytes = Base64.decode(preferenceManager.getString("my_signed_prekey_public"), Base64.NO_WRAP);
                            byte[] myPqPrivBytes = Base64.decode(preferenceManager.getString("my_pq_private"), Base64.NO_WRAP);

                            KeyFactory kf = KeyFactory.getInstance("X25519", "BC");
                            KeyPair myIdentity = new KeyPair(kf.generatePublic(new X509EncodedKeySpec(myIdPubBytes)), kf.generatePrivate(new PKCS8EncodedKeySpec(myIdPrivBytes)));
                            KeyPair mySignedPreKey = new KeyPair(kf.generatePublic(new X509EncodedKeySpec(mySpkPubBytes)), kf.generatePrivate(new PKCS8EncodedKeySpec(mySpkPrivBytes)));
                            sessionRatchet = X3DHManager.receiveHandshake(myIdentity, mySignedPreKey, myPqPrivBytes, data.getString("x3dh_ik"), data.getString("x3dh_ek"), data.getString("pq_ct"));
                        } catch (Exception e) {}
                    }

                    if (sessionRatchet != null) {
                        try {
                            byte[] dhPubBytes = Base64.decode(data.getString("dhPub"), Base64.NO_WRAP);
                            RatchetHeader header = new RatchetHeader(dhPubBytes, data.getInt("pn"), data.getInt("n"));
                            header.ciphertext = Base64.decode(ciphertextStr, Base64.NO_WRAP);
                            KeyFactory kf = KeyFactory.getInstance("X25519", "BC");
                            byte[] decrypted = sessionRatchet.decrypt(header, kf.generatePublic(new X509EncodedKeySpec(dhPubBytes)));
                            String decryptedString = new String(decrypted, StandardCharsets.UTF_8);

                            String messageType, messageContent;
                            try {
                                JSONObject payload = new JSONObject(decryptedString);
                                messageType = payload.getString("type"); messageContent = payload.getString("data");
                            } catch (JSONException e) { messageType = "text"; messageContent = decryptedString; }

                            saveSessionState();
                            ChatMessage chatMessage = new ChatMessage();
                            chatMessage.senderId = receiverUser.id;
                            chatMessage.message = messageContent; chatMessage.type = messageType;
                            chatMessage.dateTime = getReadableDateTime(new Date());

                            try { localDB.saveMessage(receiverUser.id, preferenceManager.getString(Constants.KEY_USER_ID), messageContent, messageType, chatMessage.dateTime); } catch (Exception ignored) {}

                            chatMessages.add(chatMessage);
                            chatAdapter.notifyItemInserted(chatMessages.size() - 1);
                            binding.chatRecyclerView.smoothScrollToPosition(chatMessages.size() - 1);
                        } catch (Exception e) {}
                    }
                    JSONObject readObj = new JSONObject();
                    readObj.put("sender", sender); readObj.put("receiver", preferenceManager.getString(Constants.KEY_PHONE));
                    mSocket.emit("message_read", readObj);
                }
            } catch (Exception e) {}
        }));
    }

    private void saveSessionState() { if (sessionRatchet != null) preferenceManager.putString("session_" + receiverUser.phone, sessionRatchet.serializeState()); }
    private void loadSessionState() { String state = preferenceManager.getString("session_" + receiverUser.phone); if (state != null) sessionRatchet = DoubleRatchet.restoreState(state); }
    private void initializeCryptoSession() {
        ApiClient.getClient().create(ApiService.class).getPreKeyBundle(receiverUser.phone).enqueue(new Callback<>() {
            public void onResponse(@NonNull Call<PreKeyBundle> call, @NonNull Response<PreKeyBundle> response) { if (response.isSuccessful()) fetchedPeerBundle = response.body(); }
            public void onFailure(@NonNull Call<PreKeyBundle> call, @NonNull Throwable t) {}
        });
    }

    private void loadChatHistory() {
        chatMessages.clear();
        chatMessages.addAll(localDB.getLocalChatHistory(preferenceManager.getString(Constants.KEY_USER_ID), receiverUser.id));
        chatAdapter.notifyDataSetChanged();
        if (!chatMessages.isEmpty()) binding.chatRecyclerView.scrollToPosition(chatMessages.size() - 1);
    }

    private void loadReceiverDetails() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) receiverUser = getIntent().getSerializableExtra(Constants.KEY_USER, User.class);
        else receiverUser = (User) getIntent().getSerializableExtra(Constants.KEY_USER);
        if (receiverUser != null) binding.textName.setText(receiverUser.name);
        else finish();
    }

    private String getReadableDateTime(Date date) { return new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(date); }
    private Bitmap getBitmapFromEncodedString(String encodedImage) {
        if (encodedImage == null || encodedImage.isEmpty()) return null;
        byte[] bytes = Base64.decode(encodedImage, Base64.DEFAULT);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }
    private void showToast(String message) { Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show(); }
}