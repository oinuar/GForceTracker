package raunio.gforcetracker;

import android.os.Environment;

import com.google.android.gms.maps.model.LatLng;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

public class Storage {

    private String path;

    public Storage() {
        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "GForceTracker");

        file.mkdirs();

        path = file.getPath();
    }

    public Object create() {
        File file = new File(path, String.format("%tFT%<tRZ.csv", Calendar.getInstance(TimeZone.getTimeZone("Z"))));

        try {
            if (!file.createNewFile() || !file.canWrite())
                file = null;
        } catch (IOException e) {
            file = null;
        }

        return file;
    }

    public void write(Object handle, LatLng latLng, float speed, float maxSpeed, float ax, float ay, float az, float axMax, float ayMax, float azMax) {
        if (handle == null)
            return;

        File file = (File)handle;

        try {
            FileOutputStream stream = new FileOutputStream((File)handle, true);

            // Write header if file is empty.
            if (file.length() == 0)
                stream.write("Timestamp\tLat\tLon\tSpeed\tMax speed\tax\tay\taz\tMax ax\tMax ay\tMax az".getBytes());

            stream.write(String.format(
                    Locale.ENGLISH, "%tFT%<tRZ\t%f\t%f\t%f\t%f\t%f\t%f\t%f\t%f\t%f\t%f",
                    Calendar.getInstance(TimeZone.getTimeZone("Z")),
                    latLng.latitude,
                    latLng.longitude,
                    speed,
                    maxSpeed,
                    ax, ay, az,
                    axMax, ayMax, azMax).getBytes());

            stream.close();
        } catch (IOException e) {

        }
    }

}
