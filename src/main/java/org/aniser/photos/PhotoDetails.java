package org.aniser.photos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.GpsDirectory;

@Component
public class PhotoDetails implements CommandLineRunner {

    @Override
    public void run(String... args) {
        main(args);
    }

    private enum GeoLocations {
        GEO_UNKNOWN(new GeoLocation(-1,-1), "geo location unknown");

        private GeoLocation geoLocation;
        private String status;

        GeoLocations(GeoLocation geoLocation, String status) {
            this.geoLocation = geoLocation;
            this.status = status;
        }
        public static boolean isEqual(GeoLocation geoLocation) {
            return geoLocation.getLatitude() == GEO_UNKNOWN.geoLocation.getLatitude()
                    && geoLocation.getLongitude() == GEO_UNKNOWN.geoLocation.getLongitude();
        }
        public static boolean invalidGeoLocation(GeoLocation geoLocation) {
            boolean geoLocationInvalid = true;
            if (geoLocation == null) {
                return geoLocationInvalid;
            }
            if (geoLocation.isZero()) {
                return geoLocationInvalid;
            }
            if (GeoLocations.isEqual(geoLocation)) {
                return geoLocationInvalid;
            }
            return ! geoLocationInvalid;
        }
        @Override
        public String toString() {
            return status;
        }
    }

    public static GeoLocation getGeoLocation(Path fileName) {
        try (InputStream str = Files.newInputStream(fileName.toAbsolutePath())) {
            Metadata metadata = ImageMetadataReader.readMetadata(str);
            Optional<GpsDirectory> gpsDirectory = Optional.ofNullable(metadata.getFirstDirectoryOfType(GpsDirectory.class));
            if(gpsDirectory.isEmpty() || GeoLocations.invalidGeoLocation(gpsDirectory.get().getGeoLocation())) {
                return GeoLocations.GEO_UNKNOWN.geoLocation;
            }
            return gpsDirectory.get().getGeoLocation();
        } catch (IOException | ImageProcessingException e){

        } finally {

        }
        throw new IllegalStateException();
    }

    private static void print(Path imageName, GeoLocation geoLocation) {
        if(GeoLocations.invalidGeoLocation(geoLocation)) {
            System.out.println(imageName + " : " + GeoLocations.GEO_UNKNOWN.toString());
            return;
        };
        System.out.println(imageName + " : " + geoLocation);
    }


    private static void process(Path path) {
        if ( path.toFile().isDirectory() ) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                stream.forEach(imageName -> process(imageName));
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else if ( path.toFile().isFile() ) {
            print(path, getGeoLocation(path));
        }
    }


    public static void main(String [] args) {
        String rootDirectoryName = "c://tmp//";

        process(Paths.get(rootDirectoryName));
    }

}
