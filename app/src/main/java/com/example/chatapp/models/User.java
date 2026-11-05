package com.example.chatapp.models;
import java.io.Serializable;

public class User implements Serializable {
    public String name, image, phone, token, id, about; // Added 'about' here
    public int unreadCount;
}