package com.spartan.dms.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DateUtil {

    private DateUtil() {
    }

    public static final String DATE_FORMAT = "dd-MM-yyyy";
    public static final String DATE_TIME_FORMAT = "dd-MM-yyyy HH:mm:ss";

    public static String formatDate(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern(DATE_FORMAT));
    }

    public static String formatDateTime(LocalDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ofPattern(DATE_TIME_FORMAT));
    }
}