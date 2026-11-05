package com.example.chatapp.models;

public class ChatMessage {
    public String senderId, receiverId, message, dateTime;
    public String sender, receiver; // Added for API mapping
    public int status; // 1=Sent, 2=Delivered
//    public String ciphertext;

    public String type;
}