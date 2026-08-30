package com.maaitlunghau.spring_boot_blueprint.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class DateTimeTestUtil {

    public static Instant now() {
        return Instant.now();
    }

    public static Instant plusMinutes(long minutes) {
        return now().plus(minutes, ChronoUnit.MINUTES);
    }

    public static Instant plusSeconds(long seconds) {
        return now().plusSeconds(seconds);
    }
    
    public static void main(String[] args) {
        Instant now = Instant.now();
        System.out.println("Now: " + now);

        System.out.println(plusMinutes(2));
    }
}
