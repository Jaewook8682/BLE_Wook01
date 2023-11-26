/*
 * Copyright (C) 2016-2020 Microchip Technology Inc. and its subsidiaries.  You may use this software and any
 * derivatives exclusively with Microchip products.
 *
 * THIS SOFTWARE IS SUPPLIED BY MICROCHIP "AS IS".  NO WARRANTIES, WHETHER EXPRESS, IMPLIED OR STATUTORY, APPLY TO THIS
 * SOFTWARE, INCLUDING ANY IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY, AND FITNESS FOR A PARTICULAR
 * PURPOSE, OR ITS INTERACTION WITH MICROCHIP PRODUCTS, COMBINATION WITH ANY OTHER PRODUCTS, OR USE IN ANY APPLICATION.
 *
 * IN NO EVENT WILL MICROCHIP BE LIABLE FOR ANY INDIRECT, SPECIAL, PUNITIVE, INCIDENTAL OR CONSEQUENTIAL LOSS, DAMAGE,
 * COST OR EXPENSE OF ANY KIND WHATSOEVER RELATED TO THE SOFTWARE, HOWEVER CAUSED, EVEN IF MICROCHIP HAS BEEN ADVISED OF
 * THE POSSIBILITY OR THE DAMAGES ARE FORESEEABLE.  TO THE FULLEST EXTENT ALLOWED BY LAW, MICROCHIP'S TOTAL LIABILITY ON
 * ALL CLAIMS IN ANY WAY RELATED TO THIS SOFTWARE WILL NOT EXCEED THE AMOUNT OF FEES, IF ANY, THAT YOU HAVE PAID
 * DIRECTLY TO MICROCHIP FOR THIS SOFTWARE.
 *
 * MICROCHIP PROVIDES THIS SOFTWARE CONDITIONALLY UPON YOUR ACCEPTANCE OF THESE TERMS.
 */

package com.microchip.mu_ble1;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.util.Hex;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

public class BleMainActivity extends AppCompatActivity {
    private final static String TAG = BleMainActivity.class.getSimpleName();

    private static final int REQ_CODE_ENABLE_BT =     1;                                            //Codes to identify activities that return results such as enabling Bluetooth
    private static final int REQ_CODE_SCAN_ACTIVITY = 2;                                            //or scanning for bluetooth devices
    private static final int REQ_CODE_ACCESS_LOC1 =   3;                                            //or requesting location access.
    private static final int REQ_CODE_ACCESS_LOC2 =   4;                                            //or requesting location access a second time.
    private static final long CONNECT_TIMEOUT =       10000;                                        //Length of time in milliseconds to try to connect to a device

    private ProgressBar progressBar;                                                                //Progress bar (indeterminate circular) to show that activity is busy connecting to BLE device
    private BleService bleService;                                                                  //Service that handles all interaction with the Bluetooth radio and remote device
    private ByteArrayOutputStream transparentUartData = new ByteArrayOutputStream();                //Stores all the incoming byte arrays received from BLE device in bleService
    private ShowAlertDialogs showAlert;                                                             //Object that creates and shows all the alert pop ups used in the app
    private Handler connectTimeoutHandler;                                                          //Handler to provide a time out if connection attempt takes too long
    private String bleDeviceName, bleDeviceAddress;                                                 //Name and address of remote Bluetooth device
    private TextView textDeviceNameAndAddress;                                                      //To show device and status information on the screen
    private TextView textTemperature;
    private enum StateConnection {DISCONNECTED, CONNECTING, DISCOVERING, CONNECTED, DISCONNECTING}  //States of the Bluetooth connection
    private StateConnection stateConnection;                                                        //State of Bluetooth connection
    private enum StateApp {STARTING_SERVICE, REQUEST_PERMISSION, ENABLING_BLUETOOTH, RUNNING}       //States of the app
    private StateApp stateApp;                                                                      //State of the app
    private LineGraphSeries<DataPoint> rxSeries;
    private double GraphHorizontalPoint = 0d;                                                  //Current horizontal position to be plotted on the graph

    private FirebaseDatabase database;
    private DatabaseReference databaseReference;
    private ArrayList<User> arrayList;

    // ----------------------------------------------------------------------------------------------------------------
    // Activity launched
    // Invoked by launcher Intent
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);                                                         //Call superclass (AppCompatActivity) onCreate method
        setContentView(R.layout.ble_main_screen);                                                   //Show the main screen - may be shown briefly if we immediately start the scan activity
        Toolbar myToolbar = findViewById(R.id.toolbar);                                             //Get a reference to the Toolbar at the top of the screen
        setSupportActionBar(myToolbar);                                                             //Treat the toolbar as an Action bar (used for app name, menu, navigation, etc.)
        progressBar = findViewById(R.id.toolbar_progress_bar);                                      //Get a reference to the progress bar
        progressBar.setIndeterminate(true);                                                         //Make the progress bar indeterminate (circular)
        progressBar.setVisibility(ProgressBar.INVISIBLE);                                           //Hide the circular progress bar
        showAlert = new ShowAlertDialogs(this);                                             //Create the object that will show alert dialogs
        stateConnection = StateConnection.DISCONNECTED;                                             //Initial stateConnection when app starts
        stateApp = StateApp.STARTING_SERVICE;                                                       //Are going to start the BleService service
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { //Check whether we have location permission, required to scan
            stateApp = StateApp.REQUEST_PERMISSION;                                                 //Are requesting Location permission
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_CODE_ACCESS_LOC1); //Request fine location permission
        }
        if (stateApp == StateApp.STARTING_SERVICE) {                                                //Only start BleService if we already have location permission
            Intent bleServiceIntent = new Intent(this, BleService.class);             //Create Intent to start the BleService
            this.bindService(bleServiceIntent, bleServiceConnection, BIND_AUTO_CREATE);             //Create and bind the new service with bleServiceConnection object that handles service connect and disconnect
        }
        connectTimeoutHandler = new Handler(Looper.getMainLooper());                                //Create a handler for a delayed runnable that will stop the connection attempt after a timeout
        textDeviceNameAndAddress = findViewById(R.id.deviceNameAndAddressText);                     //Get a reference to the TextView that will display the device name and address
        textTemperature = findViewById(R.id.temperatureTextView);                                   //Get a reference to the TextView that will display the temperature

        //Firebase
        arrayList = new ArrayList<>();
        database = FirebaseDatabase.getInstance();
        databaseReference = database.getReference("User");

        Button bt_save_ = findViewById(R.id.bt_save);
        EditText et_save1_ = findViewById(R.id.et_save1);
        EditText et_save2_ = findViewById(R.id.et_save2);
        bt_save_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save_data(et_save1_.getText().toString(), et_save2_.getText().toString());
                et_save1_.setText(null);
                et_save2_.setText(null);
            }
        });

        GraphView rxGraph_ = findViewById(R.id.rxGraph);                               //Get a reference to the GraphView that will plot the accelerometer value
        rxSeries = new LineGraphSeries<>();
        rxSeries.setColor(Color.BLUE);
        rxGraph_.addSeries(rxSeries);

        Button bt_send_ = findViewById(R.id.bt_send);
        EditText et_send_ = findViewById(R.id.et_send);

        bt_send_.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String sendData = et_send_.getText().toString();
                bleService.writeToTransparentUART(sendData.getBytes());
                et_send_.setText(null);
            }
        });
    }

    private void save_data(String name, String value) {
        User user = new User(name, value);
        databaseReference.child("test1").setValue(user);
    }


    // ----------------------------------------------------------------------------------------------------------------
    // Activity started
    // Nothing needed here, all done in onCreate() and onResume()
    @Override
    public void onStart() {
        super.onStart();                                                                            //Call superclass (AppCompatActivity) onStart method
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Activity resumed
    // Register the receiver for Intents from the BleService
    @Override
    protected void onResume() {
        super.onResume();                                                                           //Call superclass (AppCompatActivity) onResume method
        try {
            registerReceiver(bleServiceReceiver, bleServiceIntentFilter());                         //Register receiver to handles events fired by the BleService
            if (bleService != null && !bleService.isBluetoothRadioEnabled())                        //Check if Bluetooth radio was turned off while app was paused
                if (stateApp == StateApp.RUNNING) {                                                 //Check that app is running, to make sure service is connected
                    stateApp = StateApp.ENABLING_BLUETOOTH;                                         //Are going to request user to turn on Bluetooth
                    stateConnection = StateConnection.DISCONNECTED;                                 //Must be disconnected if Bluetooth is off
                    startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_CODE_ENABLE_BT); //Start the activity to ask the user to grant permission to enable Bluetooth
                    Log.i(TAG, "Requesting user to enable Bluetooth radio");
                }
            updateConnectionState();                                                                //Update the screen and menus
        } catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Activity paused
    // Unregister the receiver for Intents from the BleService
    @Override
    protected void onPause() {
        super.onPause();                                                                            //Call superclass (AppCompatActivity) onPause method
        unregisterReceiver(bleServiceReceiver);                                                     //Unregister receiver that was registered in onResume()
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Activity stopped
    // Nothing needed here, all done in onPause() and onDestroy()
    @Override
    public void onStop() {
        super.onStop();                                                                             //Call superclass (AppCompatActivity) onStop method
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Activity is ending
    // Unbind from BleService and save the details of the BLE device for next time
    @Override
    protected void onDestroy() {
        super.onDestroy();                                                                          //Call superclass (AppCompatActivity) onDestroy method
        if (stateApp != StateApp.REQUEST_PERMISSION) {                                              //See if we got past the permission request
            unbindService(bleServiceConnection);                                                    //Unbind from the service handling Bluetooth
        }
    }

    /******************************************************************************************************************
     * Methods for handling menu creation and operation.
     */

    // ----------------------------------------------------------------------------------------------------------------
    // Options menu is different depending on whether connected or not and if we have permission to scan
    // Show Disconnect option if we are connected or show Connect option if not connected and have a device address
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.ble_main_menu, menu);                                      //Show the menu
        if (stateApp == StateApp.RUNNING) {                                                         //See if we have permission, service started and Bluetooth enabled
            menu.findItem(R.id.menu_scan).setVisible(true);                                         //Scan menu item
            if (stateConnection == StateConnection.CONNECTED) {                                     //See if we are connected
                menu.findItem(R.id.menu_disconnect).setVisible(true);                               //Are connected so show Disconnect menu
                menu.findItem(R.id.menu_connect).setVisible(false);                                 //and hide Connect menu
            }
            else {                                                                                  //Else are not connected so
                menu.findItem(R.id.menu_disconnect).setVisible(false);                              // hide the disconnect menu
                if (bleDeviceAddress != null) {                                                     //See if we have a device address
                    menu.findItem(R.id.menu_connect).setVisible(true);                              // then show the connect menu
                }
                else {                                                                              //Else no device address so
                    menu.findItem(R.id.menu_connect).setVisible(false);                             // hide the connect menu
                }
            }
        }
        else {
            menu.findItem(R.id.menu_scan).setVisible(false);                                        //No permission so hide scan menu item
            menu.findItem(R.id.menu_connect).setVisible(false);                                     //and hide Connect menu
            menu.findItem(R.id.menu_disconnect).setVisible(false);                                  //Are not connected so hide the disconnect menu
        }
        return true;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Menu item selected
    // Scan, connect or disconnect, etc.
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        try {
            switch (item.getItemId()) {
                case R.id.menu_scan: {                                                              //Menu option Scan chosen
                    startBleScanActivity();                                                         //Launch the BleScanActivity to scan for BLE devices
                    return true;
                }
                case R.id.menu_connect: {                                                           //Menu option Connect chosen
                    if (bleDeviceAddress != null) {                                                 //Check that there is a valid Bluetooth LE address
                        stateConnection = StateConnection.CONNECTING;                               //Have an address so we are going to start connecting
                        connectWithAddress(bleDeviceAddress);                                       //Call method to ask the BleService to connect
                    }
                    return true;
                }
                case R.id.menu_disconnect: {                                                        //Menu option Disconnect chosen
                    stateConnection = StateConnection.DISCONNECTING;                                //StateConnection is used to determine whether disconnect event should trigger a popup to reconnect
                    updateConnectionState();                                                        //Update the screen and menus
                    bleService.disconnectBle();                                                     //Ask the BleService to disconnect from the Bluetooth device
                    return true;
                }
                case R.id.menu_help: {                                                              //Menu option Help chosen
                    showAlert.showHelpMenuDialog(this.getApplicationContext());                     //Show the AlertDialog that has the Help text
                    return true;
                }
                case R.id.menu_about: {                                                             //Menu option About chosen
                    showAlert.showAboutMenuDialog(this);                                    //Show the AlertDialog that has the About text
                    return true;
                }
                case R.id.menu_exit: {                                                              //Menu option Exit chosen
                    showAlert.showExitMenuDialog(new Runnable() {                                   //Show the AlertDialog that has the Exit warning text
                        @Override
                        public void run() {                                                         //Runnable to execute if OK button pressed
                            if (bleService != null) {                                               //Check if the service is running
                                bleService.disconnectBle();                                         //Ask the BleService to disconnect in case there is a Bluetooth connection
                            }
                            onBackPressed();                                                        //Exit by going back - ultimately calls finish()
                        }
                    });
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
        return super.onOptionsItemSelected(item);                                                   //No valid menu item selected so pass up to superclass method
    }

    /******************************************************************************************************************
     * Callback methods for handling Service connection events and Activity result events.
     */

    // ----------------------------------------------------------------------------------------------------------------
    // Callbacks for BleService service connection and disconnection
    private final ServiceConnection bleServiceConnection = new ServiceConnection() {                //Create new ServiceConnection interface to handle connection and disconnection

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {              //Service connects
            try {
                Log.i(TAG, "BleService connected");
                BleService.LocalBinder binder = (BleService.LocalBinder) service;                   //Get the Binder for the Service
                bleService = binder.getService();                                                   //Get a link to the Service from the Binder
                if (bleService.isBluetoothRadioEnabled()) {                                         //See if the Bluetooth radio is on
                    stateApp = StateApp.RUNNING;                                                    //Service is running and Bluetooth is enabled, app is now fully operational
                    startBleScanActivity();                                                         //Launch the BleScanActivity to scan for BLE devices
                }
                else {                                                                              //Radio needs to be enabled
                    stateApp = StateApp.ENABLING_BLUETOOTH;                                         //Are requesting Bluetooth to be turned on
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);     //Create an Intent asking the user to grant permission to enable Bluetooth
                    startActivityForResult(enableBtIntent, REQ_CODE_ENABLE_BT);                     //Send the Intent to start the Activity that will return a result based on user input
                    Log.i(TAG, "Requesting user to turn on Bluetooth");
                }
            } catch (Exception e) {
                Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {                            //BleService disconnects - should never happen
            Log.i(TAG, "BleService disconnected");
            bleService = null;                                                                      //Not bound to BleService
        }
    };

    // ----------------------------------------------------------------------------------------------------------------
    // Callback for Activities that return a result
    // We call BluetoothAdapter to turn on the Bluetooth radio and BleScanActivity to scan
    // and return the name and address of a Bluetooth LE device that the user chooses
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);                                    //Pass the activity result up to the parent method
        switch (requestCode) {                                                                      //See which Activity returned the result
            case REQ_CODE_ENABLE_BT: {
                if (resultCode == Activity.RESULT_OK) {                                             //User chose to enable Bluetooth
                    stateApp = StateApp.RUNNING;                                                    //Service is running and Bluetooth is enabled, app is now fully operational
                    startBleScanActivity();                                                         //Start the BleScanActivity to do a scan for devices
                } else {
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);     //User chose not to enable Bluetooth so create an Intent to ask again
                    startActivityForResult(enableBtIntent, REQ_CODE_ENABLE_BT);                     //Send the Intent to start the activity that will return a result based on user input
                    Log.i(TAG, "Requesting user to turn on Bluetooth again");
                }
                break;
            }
            case REQ_CODE_SCAN_ACTIVITY: {
                showAlert.dismiss();
                if (resultCode == Activity.RESULT_OK) {                                             //User chose a Bluetooth device to connect
                    stateApp = StateApp.RUNNING;                                                    //Service is running and Bluetooth is enabled, app is fully operational
                    bleDeviceAddress = intent.getStringExtra(BleScanActivity.EXTRA_SCAN_ADDRESS);   //Get the address of the BLE device selected in the BleScanActivity
                    bleDeviceName = intent.getStringExtra(BleScanActivity.EXTRA_SCAN_NAME);         //Get the name of the BLE device selected in the BleScanActivity
                    if (bleDeviceAddress == null) {                                                 //Check whether we were given a device address
                        stateConnection = StateConnection.DISCONNECTED;                             //No device address so not connected and not going to connect
                    } else {
                        stateConnection = StateConnection.CONNECTING;                               //Got an address so we are going to start connecting
                        connectWithAddress(bleDeviceAddress);                                       //Initiate a connection
                    }
                } else {                                                                            //Did not get a valid result from the BleScanActivity
                    stateConnection = StateConnection.DISCONNECTED;                                 //No result so not connected and not going to connect
                }
                updateConnectionState();                                                            //Update the connection state on the screen and menus
                break;
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Callback for permission requests (new feature of Android Marshmallow requires runtime permission requests)
    @TargetApi(Build.VERSION_CODES.M)
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {                                 //See if location permission was granted
            Log.i(TAG, "Location permission granted");
            stateApp = StateApp.STARTING_SERVICE;                                                   //Are going to start the BleService service
            Intent bleServiceIntent = new Intent(this, BleService.class);             //Create Intent to start the BleService
            this.bindService(bleServiceIntent, bleServiceConnection, BIND_AUTO_CREATE);             //Create and bind the new service to bleServiceConnection object that handles service connect and disconnect
        }
        else if (requestCode == REQ_CODE_ACCESS_LOC1) {                                             //Not granted so see if first refusal and need to ask again
            showAlert.showLocationPermissionDialog(new Runnable() {                                 //Show the AlertDialog that scan cannot be performed without permission
                @TargetApi(Build.VERSION_CODES.M)
                @Override
                public void run() {                                                                 //Runnable to execute when Continue button pressed
                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_CODE_ACCESS_LOC2); //Ask for location permission again
                }
            });
        }
        else {                                                                                      //Permission refused twice so send user to settings
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);               //Create Intent to open the app settings page
            Uri uri = Uri.fromParts("package", getPackageName(), null);            //Identify the package for the settings
            intent.setData(uri);                                                                    //Add the package to the Intent
            startActivity(intent);                                                                  //Start the settings activity
        }
    }

    /******************************************************************************************************************
     * Methods for handling Intents.
     */

    // ----------------------------------------------------------------------------------------------------------------
    // Method to create and return an IntentFilter with Intent Actions that will be broadcast by the BleService to the bleServiceReceiver BroadcastReceiver
    private static IntentFilter bleServiceIntentFilter() {                                          //Method to create and return an IntentFilter
        final IntentFilter intentFilter = new IntentFilter();                                       //Create a new IntentFilter
        intentFilter.addAction(BleService.ACTION_BLE_CONNECTED);                                    //Add filter for receiving an Intent from BleService announcing a new connection
        intentFilter.addAction(BleService.ACTION_BLE_DISCONNECTED);                                 //Add filter for receiving an Intent from BleService announcing a disconnection
        intentFilter.addAction(BleService.ACTION_BLE_DISCOVERY_DONE);                               //Add filter for receiving an Intent from BleService announcing a service discovery
        intentFilter.addAction(BleService.ACTION_BLE_DISCOVERY_FAILED);                             //Add filter for receiving an Intent from BleService announcing failure of service discovery
        intentFilter.addAction(BleService.ACTION_BLE_NEW_DATA_RECEIVED);                            //Add filter for receiving an Intent from BleService announcing new data received
        Log.d("**", "initialized Intent");
        return intentFilter;                                                                        //Return the new IntentFilter
    }

    // ----------------------------------------------------------------------------------------------------------------
    // BroadcastReceiver handles various Intents sent by the BleService service.
    private final BroadcastReceiver bleServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {                                     //Intent received
            final String action = intent.getAction();                                               //Get the action String from the Intent
            switch (action) {                                                                       //See which action was in the Intent
                case BleService.ACTION_BLE_CONNECTED: {                                             //Have connected to BLE device
                    Log.d(TAG, "Received Intent  ACTION_BLE_CONNECTED");
                    initializeDisplay();                                                            //Clear the temperature and accelerometer text and graphs
                    transparentUartData.reset();                                                    //Also clear any buffered incoming data
                    stateConnection = StateConnection.DISCOVERING;                                  //BleService automatically starts service discovery after connecting
                    updateConnectionState();                                                        //Update the screen and menus
                    break;
                }
                case BleService.ACTION_BLE_DISCONNECTED: {                                          //Have disconnected from BLE device
                    Log.d(TAG, "Received Intent ACTION_BLE_DISCONNECTED");
                    initializeDisplay();                                                            //Clear the temperature and accelerometer text and graphs
                    transparentUartData.reset();                                                    //Also clear any buffered incoming data
                    if (stateConnection == StateConnection.CONNECTED) {                             //See if we were connected before
                        showAlert.showLostConnectionDialog(new Runnable() {                         //Show the AlertDialog for a lost connection
                            @Override
                            public void run() {                                                     //Runnable to execute if OK button pressed
                                startBleScanActivity();                                             //Launch the BleScanActivity to scan for BLE devices
                            }
                        });
                    }
                    stateConnection = StateConnection.DISCONNECTED;                                 //Are disconnected
                    updateConnectionState();                                                        //Update the screen and menus
                    break;
                }
                case BleService.ACTION_BLE_DISCOVERY_DONE: {                                        //Have completed service discovery
                    Log.d(TAG, "Received Intent  ACTION_BLE_DISCOVERY_DONE");
                    connectTimeoutHandler.removeCallbacks(abandonConnectionAttempt);                //Stop the connection timeout handler from calling the runnable to stop the connection attempt
                    stateConnection = StateConnection.CONNECTED;                                    //Were already connected but showing discovering, not connected
                    updateConnectionState();                                                        //Update the screen and menus
                    break;
                }
                case BleService.ACTION_BLE_DISCOVERY_FAILED: {                                      //Service discovery failed to find the right service and characteristics
                    Log.d(TAG, "Received Intent  ACTION_BLE_DISCOVERY_FAILED");
                    stateConnection = StateConnection.DISCONNECTING;                                //Were already connected but showing discovering, so are now disconnecting
                    connectTimeoutHandler.removeCallbacks(abandonConnectionAttempt);                //Stop the connection timeout handler from calling the runnable to stop the connection attempt
                    bleService.disconnectBle();                                                     //Ask the BleService to disconnect from the Bluetooth device
                    updateConnectionState();                                                        //Update the screen and menus
                    showAlert.showFaultyDeviceDialog(new Runnable() {                               //Show the AlertDialog for a faulty device
                        @Override
                        public void run() {                                                         //Runnable to execute if OK button pressed
                            startBleScanActivity();                                                 //Launch the BleScanActivity to scan for BLE devices
                        }
                    });
                    break;
                }
                case BleService.ACTION_BLE_NEW_DATA_RECEIVED: {                                     //Have received data (characteristic notification) from BLE device
                    Log.d(TAG, "Received Intent ACTION_BLE_NEW_DATA_RECEIVED");
                    final byte[] newBytes = bleService.readFromTransparentUART();
                    processIncomingData(newBytes);
                    break;
                }
                default: {
                    Log.w(TAG, "Received Intent with invalid action: " + action);
                }
            }
        }
    };

    /******************************************************************************************************************
     * Method for processing incoming data and updating the display
     */

    private void initializeDisplay() {
        try {
            rxSeries.resetData(new DataPoint[0]);
            GraphHorizontalPoint = 0d;
        } catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    private void processIncomingData(byte[] newBytes) {
        try {
            /*
            transparentUartData.write(newBytes);
            Log.d("!!!!", String.valueOf(newBytes));
            final byte[] allBytes = transparentUartData.toByteArray();                          //Put all the bytes into a byte array

            byte[] newLine = Arrays.copyOf(allBytes, allBytes.length);
            byte[] leftOver = Arrays.copyOfRange(allBytes, 0, allBytes.length);

            BigInteger bigInteger = new BigInteger(1, newLine);
            String result = String.format("%x", bigInteger);
            Log.d("@%%@%@", "RESULT : " + result);
            String hex = new java.math.BigInteger(newLine).toString(16);
            Log.d("%%@@", hex);
            transparentUartData.reset();
            transparentUartData.write(leftOver);
            //final String newLineStr = new String(newLine, StandardCharsets.UTF_8);              //Create a string from the bytes up to the termination byte

            //textTemperature.setText(textTemperature.getText() + "\n" + "New data received" + "\n" + "0X"+result+ "   Received");
            textTemperature.setText(result);
            System.out.println("~_~"+result.equals(textTemperature.getText()));
             */

            textTemperature.setText(textTemperature.getText() + "\n" + "New data received" + "\n" + Hex.bytesToStringUppercase(newBytes));


        } catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    /******************************************************************************************************************
     * Methods for scanning, connecting, and showing event driven dialogs
     */

    // ----------------------------------------------------------------------------------------------------------------
    // Start the BleScanActivity that scans for available Bluetooth devices and lets the user select one
    private void startBleScanActivity() {
        try {
            if (stateApp == StateApp.RUNNING) {                                                     //Only do a scan if we got through startup (permission granted, service started, Bluetooth enabled)
                stateConnection = StateConnection.DISCONNECTING;                                    //Are disconnecting prior to doing a scan
                bleService.disconnectBle();                                                         //Disconnect an existing Bluetooth connection or cancel a connection attempt
                final Intent bleScanActivityIntent = new Intent(BleMainActivity.this, BleScanActivity.class); //Create Intent to start the BleScanActivity
                startActivityForResult(bleScanActivityIntent, REQ_CODE_SCAN_ACTIVITY);              //Start the BleScanActivity
            }
        } catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Attempt to connect to a Bluetooth device given its address and time out after CONNECT_TIMEOUT milliseconds
    private void connectWithAddress(String address) {
        try {
            updateConnectionState();                                                                //Update the screen and menus (stateConnection is either CONNECTING or AUTO_CONNECT
            connectTimeoutHandler.postDelayed(abandonConnectionAttempt, CONNECT_TIMEOUT);           //Start a delayed runnable to time out if connection does not occur
            bleService.connectBle(address);                                                         //Ask the BleService to connect to the device
        } catch (Exception e) {
            Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Runnable used by the connectTimeoutHandler to stop the connection attempt
    private Runnable abandonConnectionAttempt = new Runnable() {
        @Override
        public void run() {
            try {
                if (stateConnection == StateConnection.CONNECTING) {                                //See if still trying to connect
                    stateConnection = StateConnection.DISCONNECTING;                                //Are now disconnecting
                    bleService.disconnectBle();                                                     //Stop the Bluetooth connection attempt in progress
                    updateConnectionState();                                                        //Update the screen and menus
                    showAlert.showFailedToConnectDialog(new Runnable() {                            //Show the AlertDialog for a connection attempt that failed
                        @Override
                        public void run() {                                                         //Runnable to execute if OK button pressed
                            startBleScanActivity();                                                 //Launch the BleScanActivity to scan for BLE devices
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Oops, exception caught in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
            }
        }
    };

    /******************************************************************************************************************
     * Methods for updating connection state on the screen
     */

    // ----------------------------------------------------------------------------------------------------------------
    // Update the text showing what Bluetooth device is connected, connecting, discovering, disconnecting, or not connected
    private void updateConnectionState() {
        runOnUiThread(new Runnable() {                                                              //Always do display updates on UI thread
            @Override
            public void run() {
                switch (stateConnection) {
                    case CONNECTING: {
                        textDeviceNameAndAddress.setText(R.string.connecting);                             //Show "Connecting"
                        progressBar.setVisibility(ProgressBar.VISIBLE);                             //Show the circular progress bar
                        break;
                    }
                    case CONNECTED: {
                        if (bleDeviceName != null) {                                                //See if there is a device name
                            textDeviceNameAndAddress.setText(bleDeviceName);                        //Display the name
                        } else {
                            textDeviceNameAndAddress.setText(R.string.unknown_device);                     //or display "Unknown Device"
                        }
                        if (bleDeviceAddress != null) {                                             //See if there is an address
                            textDeviceNameAndAddress.append(" - " + bleDeviceAddress);              //Display the address
                        }
                        progressBar.setVisibility(ProgressBar.INVISIBLE);                           //Hide the circular progress bar
                        break;
                    }
                    case DISCOVERING: {
                        textDeviceNameAndAddress.setText(R.string.discovering);                            //Show "Discovering"
                        progressBar.setVisibility(ProgressBar.VISIBLE);                             //Show the circular progress bar
                        break;
                    }
                    case DISCONNECTING: {
                        textDeviceNameAndAddress.setText(R.string.disconnecting);                          //Show "Disconnectiong"
                        progressBar.setVisibility(ProgressBar.INVISIBLE);                           //Hide the circular progress bar
                        break;
                    }
                    case DISCONNECTED:
                    default: {
                        stateConnection = StateConnection.DISCONNECTED;                             //Default, in case state is unknown
                        textDeviceNameAndAddress.setText(R.string.not_connected);                          //Show "Not Connected"
                        progressBar.setVisibility(ProgressBar.INVISIBLE);                           //Hide the circular progress bar
                        break;
                    }
                }
                invalidateOptionsMenu();                                                            //Update the menu to reflect the connection state stateConnection
            }
        });
    }
}
