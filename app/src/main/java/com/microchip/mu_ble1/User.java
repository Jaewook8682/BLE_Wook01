package com.microchip.mu_ble1;

public class User {
    String name;
    String value;
    public User(){}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public User(String name, String value){
        this.name = name;
        this.value = value;
    }
}
