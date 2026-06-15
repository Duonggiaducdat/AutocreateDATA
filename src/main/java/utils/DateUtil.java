package utils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Date helpers used by the FTMS page objects.
 */
public final class DateUtil {

    private static final DateTimeFormatter FILE_TS  = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter DD_MM_YY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private DateUtil() {}

    /** Compact timestamp suitable for filenames / unique opportunity names. */
    public static String getTimeForFileName() {
        return LocalDateTime.now().format(FILE_TS);
    }

    /** Today as {@code dd/MM/yyyy}. */
    public static String getToday() {
        return LocalDate.now().format(DD_MM_YY);
    }

    /** Today + 1 month as {@code dd/MM/yyyy}. */
    public static String getPlusMonth() {
        return LocalDate.now().plusMonths(1).format(DD_MM_YY);
    }
}
