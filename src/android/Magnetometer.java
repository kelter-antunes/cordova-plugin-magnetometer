/**
*   Magnetometer.java
*
*   A Java Class for the Cordova Magnetometer Plugin
*
*   @by Steven de Salas (desalasworks.com | github/sdesalas)
*   @licence MIT
*
*   @see https://github.com/sdesalas/cordova-plugin-magnetometer
*   @see https://github.com/apache/cordova-plugin-device-orientation
*   @see http://www.techrepublic.com/article/pro-tip-create-your-own-magnetic-compass-using-androids-internal-sensors/
*   
*/

package org.apache.cordova.magnetometer;

import java.util.List;
import java.util.ArrayList;
import java.lang.Math;

import org.apache.cordova.CordovaWebView;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.content.Context;

import android.os.Handler;
import android.os.Looper;


public class Magnetometer extends CordovaPlugin implements SensorEventListener  {

    public static int STOPPED = 0;
    public static int STARTING = 1;
    public static int RUNNING = 2;
    public static int ERROR_FAILED_TO_START = 3;

    public long TIMEOUT = 30000;        // Timeout in msec to shut off listener

    int status;                         // status of listener
    float ax;                            // accelerometer x value
    float ay;                            // accelerometer y value
    float az;                            // accelerometer z value
    float mx;                            // magnetometer x value
    float my;                            // magnetometer y value
    float mz;                            // magnetometer z value
    float degrees;                      // magnetometer degrees value (magnetic heading)
    float magnitude;                    // magnetometer calculated magnitude
    private final float[] accelerometerReading = new float[3];
    private final float[] magnetometerReading = new float[3];
    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];

    long timeStamp;                     // time of most recent value
    long lastAccessTime;                // time the value was last retrieved

    private SensorManager sensorManager;// Sensor manager
    Sensor aSensor;                     // Accelerometer sensor returned by sensor manager
    Sensor mSensor;                     // Magnetic sensor returned by sensor manager

    private CallbackContext callbackContext;
    List<CallbackContext> watchContexts;

    public Magnetometer() {
        this.ax = 0;
        this.ay = 0;
        this.az = 0;
        this.mx = 0;
        this.my = 0;
        this.mz = 0;
        this.degrees = 0;
        this.timeStamp = 0;
        this.watchContexts = new ArrayList<CallbackContext>();
        this.setStatus(Magnetometer.STOPPED);
    }

    public void onDestroy() {
        this.stop();
    }

    public void onReset() {
        this.stop();
    }

    //--------------------------------------------------------------------------
    // Cordova Plugin Methods
    //--------------------------------------------------------------------------

    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        this.sensorManager = (SensorManager) cordova.getActivity().getSystemService(Context.SENSOR_SERVICE);
    }

    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("start")) {
            this.start();
        }
        else if (action.equals("stop")) {
            this.stop();
        }
        else if (action.equals("getStatus")) {
            int i = this.getStatus();
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, i));
        }
        else if (action.equals("getReading")) {
            // If not running, then this is an async call, so don't worry about waiting
            if (this.status != Magnetometer.RUNNING) {
                int r = this.start();
                if (r == Magnetometer.ERROR_FAILED_TO_START) {
                    callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.IO_EXCEPTION, Magnetometer.ERROR_FAILED_TO_START));
                    return false;
                }
                // Set a timeout callback on the main thread.
                Handler handler = new Handler(Looper.getMainLooper());
                handler.postDelayed(new Runnable() {
                    public void run() {
                        Magnetometer.this.timeout();
                    }
                }, 2000);
            }
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, getReading()));
        } else {
            // Unsupported action
            return false;
        }
        return true;
    }

    //--------------------------------------------------------------------------
    // Local Methods
    //--------------------------------------------------------------------------

    /**
     * Start listening for compass sensor.
     *
     * @return          status of listener
     */
    public int start() {

        // If already starting or running, then just return
        if ((this.status == Magnetometer.RUNNING) || (this.status == Magnetometer.STARTING)) {
            return this.status;
        }

        // Get magnetic field sensor from sensor manager
        @SuppressWarnings("deprecation")
        //aSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        //if (aSensor != null) {
        //    sensorManager.registerListener(this, aSensor, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
        //}
        //mSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        //if (mSensor != null) {
        //    sensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
        //}
        
        List<Sensor> alist = this.sensorManager.getSensorList(Sensor.TYPE_ACCELEROMETER);
        List<Sensor> mlist = this.sensorManager.getSensorList(Sensor.TYPE_MAGNETIC_FIELD);
        //if (aSensor != null && mSensor != null) {

        // If found, then register as listener
        if (alist != null && alist.size() > 0 && mlist != null && mlist.size() > 0) {
            this.aSensor = alist.get(0);
            this.sensorManager.registerListener(this, this.aSensor, SensorManager.SENSOR_DELAY_NORMAL);
            this.mSensor = mlist.get(0);
            this.sensorManager.registerListener(this, this.mSensor, SensorManager.SENSOR_DELAY_NORMAL);
            this.lastAccessTime = System.currentTimeMillis();
            this.setStatus(Magnetometer.STARTING);
        }

        // If error, then set status to error
        else {
            this.setStatus(Magnetometer.ERROR_FAILED_TO_START);
        }

        return this.status;
    }

    /**
     * Stop listening to compass sensor.
     */
    public void stop() {
        if (this.status != Magnetometer.STOPPED) {
            this.sensorManager.unregisterListener(this);
        }
        this.setStatus(Magnetometer.STOPPED);
    }

    /**
     * Called after a delay to time out if the listener has not attached fast enough.
     */
    private void timeout() {
        if (this.status == Magnetometer.STARTING) {
            this.setStatus(Magnetometer.ERROR_FAILED_TO_START);
            if (this.callbackContext != null) {
                this.callbackContext.error("Magnetometer listener failed to start.");
            }
        }
    }

    //--------------------------------------------------------------------------
    // SensorEventListener Interface
    //--------------------------------------------------------------------------

    /**
     * Sensor listener event.
     *
     * @param event
     */
    public void onSensorChanged(SensorEvent event) {
        if (event == null) {
            return;
        }

        final float alpha = 0.97f;
        synchronized (this) {
            this.timeStamp = System.currentTimeMillis();
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.length);
                accelerometerReading[0] = alpha * accelerometerReading[0] + (1 - alpha) * event.values[0];
                accelerometerReading[1] = alpha * accelerometerReading[1] + (1 - alpha) * event.values[1];
                accelerometerReading[2] = alpha * accelerometerReading[2] + (1 - alpha) * event.values[2];
            } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.length);
                magnetometerReading[0] = alpha * magnetometerReading[0] + (1 - alpha) * event.values[0];
                magnetometerReading[1] = alpha * magnetometerReading[1] + (1 - alpha) * event.values[1];
                magnetometerReading[2] = alpha * magnetometerReading[2] + (1 - alpha) * event.values[2];
            }
    
            boolean success = SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading);
            if (success) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles);
                this.ax = accelerometerReading[0];
                this.ay = accelerometerReading[1];
                this.az = accelerometerReading[2];
                this.mx = magnetometerReading[0];
                this.my = magnetometerReading[1];
                this.mz = magnetometerReading[2];
                this.degrees = ((float) Math.toDegrees(orientationAngles[0]) + 360) % 360;
            } else {
                this.ax = accelerometerReading[0];
                this.ay = accelerometerReading[1];
                this.az = accelerometerReading[2];
                this.mx = magnetometerReading[0];
                this.my = magnetometerReading[1];
                this.mz = magnetometerReading[2];
                this.degrees = 360;
            }    
        }
        // If heading hasn't been read for TIMEOUT time, then turn off compass sensor to save power
        if ((this.timeStamp - this.lastAccessTime) > this.TIMEOUT) {
            this.stop();
        }
        
    }

    /**
     * Required by SensorEventListener
     * @param sensor
     * @param accuracy
     */
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // DO NOTHING
    }

    // ------------------------------------------------
    // JavaScript Interaction
    // ------------------------------------------------

    /**
     * Get status of magnetic sensor.
     *
     * @return          status
     */
    public int getStatus() {
        return this.status;
    }

    /**
     * Set the status and send it to JavaScript.
     * @param status
     */
    private void setStatus(int status) {
        this.status = status;
    }

    /**
     * Create the Reading JSON object to be returned to JavaScript
     *
     * @return a magnetic sensor reading
     */
    private JSONObject getReading() throws JSONException {
        JSONObject obj = new JSONObject();

        obj.put("ax", this.ax);
        obj.put("ay", this.ay);
        obj.put("az", this.az);
        obj.put("mx", this.mx);
        obj.put("my", this.my);
        obj.put("mz", this.mz);
        obj.put("degrees", this.degrees);

        double x2 = Float.valueOf(this.mx * this.mx).doubleValue();
        double y2 = Float.valueOf(this.my * this.my).doubleValue();
        double z2 = Float.valueOf(this.mz * this.mz).doubleValue();

        obj.put("magnitude", Math.sqrt(x2 + y2 + z2));

        return obj;
    }
}
