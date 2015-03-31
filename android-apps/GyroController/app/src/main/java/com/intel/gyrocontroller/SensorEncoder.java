package com.intel.gyrocontroller;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by dancat on 23/02/15.
 */
public class SensorEncoder implements SensorEventListener {
    private SensorManager mSensorManager;
    private Sensor mGyroscope;
    private Handler mSensorHandler;
    private float[] mDefaultVector = new float[]{0, 0, 0};
    private int[] mEncodedDirection = new int[]{0, 0, 0};
    private boolean calibrating = false;
    private boolean calibrated = false;

    public static final int NEW_DATA = 0;
    public static final int CALIBRATION_COMPLETE = 1;

    public static final Map<Integer, Integer> FB_MAP;
    public static final Map<Integer, Integer> LR_MAP;
    public static final Map<Integer, Integer> YAW_MAP;

    static {
        Map<Integer, Integer>  mapFB = new HashMap<>();
        Map<Integer, Integer>  mapLR = new HashMap<>();
        Map<Integer, Integer>  mapYAW = new HashMap<>();
        mapFB.put(-3, 127);
        mapFB.put(-2, 105);
        mapFB.put(-1, 85);
        mapFB.put(0, 63);
        mapFB.put(1, 35);
        mapFB.put(2, 15);
        mapFB.put(3, 1);

        mapLR.put(-3, 127);
        mapLR.put(-2, 95);
        mapLR.put(-1, 80);
        mapLR.put(0, 63);
        mapLR.put(1, 40);
        mapLR.put(2, 25);
        mapLR.put(3, 1);

        mapYAW.put(-3, 127);
        mapYAW.put(-2, 105);
        mapYAW.put(-1, 85);
        mapYAW.put(0, 63);
        mapYAW.put(1, 35);
        mapYAW.put(2, 15);
        mapYAW.put(3, 1);
        FB_MAP = Collections.unmodifiableMap(mapFB);
        LR_MAP = Collections.unmodifiableMap(mapLR);
        YAW_MAP = Collections.unmodifiableMap(mapYAW);
    }

    public SensorEncoder(Context context, Handler messengeHandler) {
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        mGyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        mSensorHandler = messengeHandler;
        mSensorManager.registerListener(this, mGyroscope, SensorManager.SENSOR_DELAY_NORMAL);
    }

    // The range of rotation is from -5 to 5.
    private synchronized void rotationToDirection(float[] values) {
        int pitch, yaw, rotation;
        rotation = (int) ((values[0] - mDefaultVector[0]) * 10.0);
        if(values[0] < 0) {
            if (values[2] > 10) {
                rotation = Math.abs(rotation);
            }
        } else {
            rotation = 0;
        }
        pitch = (int)((values[1] - mDefaultVector[1]) * 10.0);
        if(values[0] > 0) {
            yaw = (int) ((values[0] - mDefaultVector[0]) * 10.0);
            if (values[2] > 10) {
                yaw = Math.abs(yaw);
            }
        } else {
            yaw = 0;
        }

        if(pitch > 3 || pitch < -3) pitch = 0;
        if(rotation > 3 || rotation < -3) rotation = 0;
        if(yaw > 3 || yaw < -3) yaw = 0;

        //mEncodedDirection = new int[] {rotation, pitch, yaw};

        //sendMessage(NEW_DATA);
        Log.i("Rotation Vectors", rotation + ", " + yaw + ", " + pitch);
    }

    public void startCalibration() {
        calibrating = true;
    }

    public void sendMessage(int type) {
        Message newMessage = mSensorHandler.obtainMessage();
        newMessage.what = type;
        if(type == NEW_DATA) newMessage.getData().putIntArray("data", mEncodedDirection);
        mSensorHandler.sendMessage(newMessage);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if(!calibrated) {
            if(calibrating && event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                mDefaultVector[0] = event.values[0];
                mDefaultVector[1] = event.values[1];
                mDefaultVector[2] = event.values[2];
                calibrating = false;
                calibrated = true;
                sendMessage(CALIBRATION_COMPLETE);
            }
            return;
        }
        if(event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) { rotationToDirection(event.values); }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

}
