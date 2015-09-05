package raunio.gforcetracker;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.speech.tts.TextToSpeech;
import android.support.v4.app.FragmentActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.LinkedList;
import java.util.Locale;

public class MapsActivity
        extends FragmentActivity
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener,
        LocationListener, TextToSpeech.OnInitListener, SensorEventListener
{
    private final int MAX_SAMPLES = 256;

    private GoogleMap map;
    private CameraPosition lastCameraPostion;

    private TextView speed, gforce, distance, lat, lon;
    private CheckBox speech_synthesis;

    private GoogleApiClient googleApi;
    private LocationRequest locationRequest;
    private Location startLocation, lastLocation;

    private TextToSpeech tts;

    private SensorManager sensorManager;
    private Sensor gravitySensor;

    private float maxSpeed, totalDistance;
    private LinkedList<float[]> sensorSamples;
    private LinkedList<Float> speedSamples;

    private Storage storage;
    private Object storageHandle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);

        locationRequest = new LocationRequest();

        locationRequest.setInterval(10000);
        locationRequest.setFastestInterval(5000);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        tts = new TextToSpeech(this, this);

        googleApi = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        storage = new Storage();

        maxSpeed = 0.f;
        totalDistance = 0.f;
        sensorSamples = new LinkedList<>();
        speedSamples = new LinkedList<>();

        initialize();
    }

    @Override
    protected void onResume() {
        super.onResume();
        initialize();
    }

    @Override
    protected void onDestroy()  {

        // Write last location.
        if (lastLocation != null) {
            float ax = getAverageSensor(0);
            float ay = getAverageSensor(1);
            float az = getAverageSensor(2);
            float axMax = getMaxSensor(0);
            float ayMax = getMaxSensor(1);
            float azMax = getMaxSensor(2);
            float speed = getAverageSpeed();
            float maxSpeed = getMaxSpeed();

            storage.write(storageHandle, new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude()), speed, maxSpeed, ax, ay, az, axMax, ayMax, azMax);
        }

        super.onDestroy();
    }

    @Override
    public void onConnected(Bundle bundle) {
        LocationServices.FusedLocationApi.requestLocationUpdates(googleApi, locationRequest, this);
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        new AlertDialog.Builder(this)
                .setTitle("Google API Client")
                .setMessage("Google API Client is not available, error code: " + connectionResult.getErrorCode())
                .setNeutralButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .show();
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts.isLanguageAvailable(Locale.ENGLISH) == TextToSpeech.LANG_AVAILABLE) {
            tts.setLanguage(Locale.ENGLISH);
            speech_synthesis.setEnabled(true);
        } else {
            speech_synthesis.setSelected(false);
            speech_synthesis.setEnabled(false);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());

        if (startLocation != null)
            totalDistance += startLocation.distanceTo(location);

        speed.setText(String.format("%.2f", location.getSpeed()));
        distance.setText(String.format("%.2f", totalDistance));
        lat.setText(latLng.latitude + "");
        lon.setText(latLng.longitude + "");

        if (speedSamples.size() > MAX_SAMPLES)
            speedSamples.removeFirst();

        speedSamples.addLast(location.getSpeed());
        startLocation = location;

        // Write initial location.
        if (lastLocation == null) {
            float ax = getAverageSensor(0);
            float ay = getAverageSensor(1);
            float az = getAverageSensor(2);
            float axMax = getMaxSensor(0);
            float ayMax = getMaxSensor(1);
            float azMax = getMaxSensor(2);
            float speed = getAverageSpeed();
            float maxSpeed = getMaxSpeed();

            storage.write(storageHandle, latLng, speed, maxSpeed, ax, ay, az, axMax, ayMax, azMax);

            lastLocation = location;
        }

        // Draw and store route to current location if moved more than 25m from last location. Also,
        // clear the sensor and speed samples.
        if (location.distanceTo(lastLocation) >= 25.f) {
            PolylineOptions lineOptions = new PolylineOptions();

            float ax = getAverageSensor(0);
            float ay = getAverageSensor(1);
            float az = getAverageSensor(2);
            float axMax = getMaxSensor(0);
            float ayMax = getMaxSensor(1);
            float azMax = getMaxSensor(2);
            double a = getTotalAcceleration(new float[] {ax, ay, az});
            double aMax = getTotalAcceleration(new float[] {axMax, ayMax, azMax});
            float speed = getAverageSpeed();
            float maxSpeed = getMaxSpeed();

            // Select line color depending on average G-force.
            if (a >= 4.f)
                lineOptions.color(Color.RED);
            else if (a >= 2.5f)
                lineOptions.color(Color.YELLOW);
            else
                lineOptions.color(Color.GREEN);

            // Map speed to line width so that greater speed equals thicker line. Like:
            // ax + b = y
            // y(11) = 5px
            // y(44) = 18px
            // max(y) = 20px
            // min(y) = 5px

            lineOptions.width(Math.max(Math.min((4.f + 1.f / 3.f) / 11.f * speed + 2.f / 3.f, 20), 5));
            lineOptions.visible(true);

            lineOptions.add(new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude()));
            lineOptions.add(latLng);

            map.addPolyline(lineOptions);

            if (maxSpeed > this.maxSpeed) {
                map.addMarker(new MarkerOptions()
                        .position(latLng)
                        .title(String.format("%.2f m/s", maxSpeed))
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));

                Say(String.format(Locale.ENGLISH, "Your new maximum speed is %.2f meters per second.", maxSpeed));
                this.maxSpeed = maxSpeed;
            }

            if (aMax >= 2.5f) {
                MarkerOptions options = new MarkerOptions()
                        .position(latLng)
                        .title(String.format("%.2f G", aMax));

                if (aMax >= 4.f)
                    options.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED));
                else
                    options.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW));

                map.addMarker(options);

                Say(String.format(Locale.ENGLISH, "Your maximum G-force during last interval was %.2f.", aMax));
            }

            storage.write(storageHandle, latLng, speed, maxSpeed, ax, ay, az, axMax, ayMax, azMax);

            lastLocation = location;

            sensorSamples.clear();
            speedSamples.clear();
        }

        // Update camera position if nobody hasn't touched it.
        if (lastCameraPostion == null || map.getCameraPosition().equals(lastCameraPostion)) {
            CameraUpdate cameraUpdate = CameraUpdateFactory.newLatLngZoom(latLng, 14);

            map.getUiSettings().setAllGesturesEnabled(false);

            map.animateCamera(cameraUpdate, new GoogleMap.CancelableCallback() {
                @Override
                public void onFinish() {
                    lastCameraPostion = map.getCameraPosition();
                    map.getUiSettings().setAllGesturesEnabled(true);
                }

                @Override
                public void onCancel() {
                    onFinish();
                }
            });
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (sensorSamples.size() > MAX_SAMPLES)
            sensorSamples.removeFirst();

        sensorSamples.addLast(event.values);

        gforce.setText(String.format("%.2f", getTotalAcceleration(event.values)));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    @TargetApi(21)
    private void Say(String text) {
        if (speech_synthesis.isEnabled() && speech_synthesis.isChecked())
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode() + "");
    }

    @TargetApi(21)
    private void toggleSpeechSynthesis(boolean on) {
        if (on)
            tts.speak("Speech synthesis is on.", TextToSpeech.QUEUE_FLUSH, null, "start");
        else
            tts.speak("Speech synthesis is off.", TextToSpeech.QUEUE_FLUSH, null, "end");
    }

    private double getTotalAcceleration(float[] values) {
        return Math.sqrt(values[0] * values[0] +
                         values[1] * values[1] +
                         values[2] * values[2]) / SensorManager.GRAVITY_EARTH;
    }

    private float getAverageSensor(int index) {
        float sum = 0;

        for (float[] value : sensorSamples)
            sum += Math.abs(value[index]);

        return sum / sensorSamples.size();
    }

    private float getAverageSpeed() {
        float sum = 0;

        for (Float value : speedSamples)
            sum += value;

        return sum / speedSamples.size();
    }

    private float getMaxSensor(int index) {
        float max = Float.MIN_VALUE;

        for (float[] value : sensorSamples)
            max = Math.max(Math.abs(value[index]), max);

        return max;
    }

    private float getMaxSpeed() {
        float max = Float.MIN_VALUE;

        for (Float value : speedSamples)
            max = Math.max(value, max);

        return max;
    }

    private void initialize() {
        lastCameraPostion = null;

        if (map == null) {
            map = ((SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map))
                    .getMap();

            map.setMyLocationEnabled(true);
            map.getUiSettings().setAllGesturesEnabled(false);
        }

        if (gravitySensor == null) {
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);

            sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        if (speed == null)
            speed = (TextView) findViewById(R.id.speed);

        if (gforce == null)
            gforce = (TextView) findViewById(R.id.gforce);

        if (distance == null)
            distance = (TextView) findViewById(R.id.distance);

        if (lat == null)
            lat = (TextView) findViewById(R.id.lat);

        if (lon == null)
            lon = (TextView) findViewById(R.id.lon);

        if (speech_synthesis == null) {
            speech_synthesis = (CheckBox) findViewById(R.id.speech_synthesis);

            speech_synthesis.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleSpeechSynthesis(((CheckBox) v).isChecked());
                }
            });
        }

        if (storageHandle == null)
            storageHandle = storage.create();

        if (!googleApi.isConnected())
            googleApi.connect();
    }
}
