package com.example.chatapp.adapters;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chatapp.R;
import com.example.chatapp.databinding.ItemContainerUserBinding;
import com.example.chatapp.listeners.UserListener;
import com.example.chatapp.models.ChatMessage;
import com.example.chatapp.models.User;
import com.example.chatapp.utilities.LocalDBManager;

import java.util.List;

public class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.UserViewHolder> {

    private final List<User> users;
    private final UserListener userListener;
    private final LocalDBManager localDB;
    private final String currentUserId;

    public UsersAdapter(List<User> users, UserListener userListener, LocalDBManager localDB, String currentUserId) {
        this.users = users;
        this.userListener = userListener;
        this.localDB = localDB;
        this.currentUserId = currentUserId;
    }

    @NonNull
    @Override
    public UserViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemContainerUserBinding binding = ItemContainerUserBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false
        );
        return new UserViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
        holder.setUserData(users.get(position));
    }

    @Override
    public int getItemCount() {
        return users.size();
    }

    class UserViewHolder extends RecyclerView.ViewHolder {
        ItemContainerUserBinding binding;

        UserViewHolder(ItemContainerUserBinding itemContainerUserBinding) {
            super(itemContainerUserBinding.getRoot());
            binding = itemContainerUserBinding;
        }

        void setUserData(User user) {
            binding.textName.setText(user.name);

            // Handle Unread E2EE Messages from Server Letterbox
            if (user.unreadCount > 0) {
                binding.textUnreadCount.setText(String.valueOf(user.unreadCount));
                binding.textUnreadCount.setVisibility(View.VISIBLE);

                binding.textLastMessage.setText("🔒 " + user.unreadCount + " new encrypted message" + (user.unreadCount > 1 ? "s" : ""));
                binding.textLastMessage.setTextColor(ContextCompat.getColor(binding.getRoot().getContext(), R.color.primary));
                binding.textLastMessage.setTypeface(null, Typeface.BOLD);
                binding.textTime.setVisibility(View.GONE);
            } else {
                // Fetch decrypted history from SQLite if no new messages are pending
                binding.textUnreadCount.setVisibility(View.GONE);
                binding.textLastMessage.setTextColor(ContextCompat.getColor(binding.getRoot().getContext(), R.color.secondary_text));
                binding.textLastMessage.setTypeface(null, Typeface.NORMAL);

                ChatMessage lastMsg = localDB.getLastMessage(currentUserId, user.id);
                if (lastMsg != null) {
                    if ("image".equals(lastMsg.type)) {
                        binding.textLastMessage.setText("📷 Photo");
                    } else {
                        binding.textLastMessage.setText(lastMsg.message);
                    }
                    binding.textTime.setText(lastMsg.dateTime);
                    binding.textTime.setVisibility(View.VISIBLE);
                } else {
                    binding.textLastMessage.setText("Tap to start chatting");
                    binding.textTime.setVisibility(View.GONE);
                }
            }

            Bitmap profilePic = getUserImage(user.image);
            if (profilePic != null) {
                binding.imageProfile.setImageBitmap(profilePic);
            } else {
                binding.imageProfile.setImageBitmap(null);
            }

            binding.getRoot().setOnClickListener(v -> userListener.onUserClicked(user));
        }
    }

    private Bitmap getUserImage(String encodedImage) {
        if (encodedImage == null || encodedImage.trim().isEmpty()) return null;
        try {
            byte[] bytes = Base64.decode(encodedImage, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }
}