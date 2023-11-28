package com.microchip.mu_ble1;

public class User {
    public String data1;
    public String data2;
    public String data3;

    public String getData1() {
        return data1;
    }

    public void setData1(String data1) {
        this.data1 = data1;
    }

    public String getData2() {
        return data2;
    }

    public void setData2(String data2) {
        this.data2 = data2;
    }

    public String getData3() {
        return data3;
    }

    public void setData3(String data3) {
        this.data3 = data3;
    }


    public User(){}

    public User(String d1, String d2, String d3){
        this.data1 = d1;
        this.data2 = d2;
        this.data3 = d3;
    }
}
