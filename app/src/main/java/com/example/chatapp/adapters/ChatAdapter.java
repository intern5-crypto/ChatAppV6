package com.example.chatapp.adapters;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.chatapp.databinding.ItemContainerReceivedMessageBinding;
import com.example.chatapp.databinding.ItemContainerSentMessageBinding;
import com.example.chatapp.models.ChatMessage;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private final List<ChatMessage> chatMessages;
    private final String senderId;
    private final Bitmap receiverProfileImage;
    private final OnImageClickListener imageClickListener; // NEW INTERFACE

    public static final int VIEW_TYPE_SENT = 1;
    public static final int VIEW_TYPE_RECEIVED = 2;

    public interface OnImageClickListener {
        void onImageClick(Bitmap bitmap);
    }

    public ChatAdapter(List<ChatMessage> chatMessages, String senderId, Bitmap receiverProfileImage, OnImageClickListener imageClickListener) {
        this.chatMessages = chatMessages;
        this.senderId = senderId;
        this.receiverProfileImage = receiverProfileImage;
        this.imageClickListener = imageClickListener; // Assign interface
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_SENT) {
            return new SentMessageViewHolder(ItemContainerSentMessageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        } else {
            return new ReceivedMessageViewHolder(ItemContainerReceivedMessageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (getItemViewType(position) == VIEW_TYPE_SENT) {
            ((SentMessageViewHolder) holder).setData(chatMessages.get(position), imageClickListener);
        } else {
            ((ReceivedMessageViewHolder) holder).setData(chatMessages.get(position), receiverProfileImage, imageClickListener);
        }
    }

    @Override
    public int getItemCount() {
        return chatMessages.size();
    }

    @Override
    public int getItemViewType(int position) {
        if (chatMessages.get(position).senderId.equals(senderId)) {
            return VIEW_TYPE_SENT;
        } else {
            return VIEW_TYPE_RECEIVED;
        }
    }

    static class SentMessageViewHolder extends RecyclerView.ViewHolder {
        private final ItemContainerSentMessageBinding binding;

        SentMessageViewHolder(ItemContainerSentMessageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void setData(ChatMessage chatMessage, OnImageClickListener listener) {
            binding.textDateTime.setText(chatMessage.dateTime);
            if (chatMessage.type != null && chatMessage.type.equals("image")) {
                try {
                    byte[] bytes = Base64.decode(chatMessage.message, Base64.DEFAULT);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap != null) {
                        binding.imageAttachment.setImageBitmap(bitmap);
                        binding.imageAttachment.setVisibility(View.VISIBLE);
                        binding.textMessage.setVisibility(View.GONE);
                        // Click Listener logic!
                        binding.imageAttachment.setOnClickListener(v -> listener.onImageClick(bitmap));
                    }
                } catch (Exception e) {
                    binding.textMessage.setText("📷 Error loading image");
                    binding.textMessage.setVisibility(View.VISIBLE);
                    binding.imageAttachment.setVisibility(View.GONE);
                }
            } else {
                binding.textMessage.setText(chatMessage.message);
                binding.textMessage.setVisibility(View.VISIBLE);
                binding.imageAttachment.setVisibility(View.GONE);
            }
        }
    }

    static class ReceivedMessageViewHolder extends RecyclerView.ViewHolder {
        private final ItemContainerReceivedMessageBinding binding;

        ReceivedMessageViewHolder(ItemContainerReceivedMessageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void setData(ChatMessage chatMessage, Bitmap receiverProfileImage, OnImageClickListener listener) {
            binding.textDateTime.setText(chatMessage.dateTime);
            if (receiverProfileImage != null) {
                binding.imageProfile.setImageBitmap(receiverProfileImage);
            }

            if (chatMessage.type != null && chatMessage.type.equals("image")) {
                try {
                    byte[] bytes = Base64.decode(chatMessage.message, Base64.DEFAULT);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap != null) {
                        binding.imageAttachment.setImageBitmap(bitmap);
                        binding.imageAttachment.setVisibility(View.VISIBLE);
                        binding.textMessage.setVisibility(View.GONE);
                        // Click Listener logic!
                        binding.imageAttachment.setOnClickListener(v -> listener.onImageClick(bitmap));
                    }
                } catch (Exception e) {
                    binding.textMessage.setText("📷 Error loading image");
                    binding.textMessage.setVisibility(View.VISIBLE);
                    binding.imageAttachment.setVisibility(View.GONE);
                }
            } else {
                binding.textMessage.setText(chatMessage.message);
                binding.textMessage.setVisibility(View.VISIBLE);
                binding.imageAttachment.setVisibility(View.GONE);
            }
        }
    }
}