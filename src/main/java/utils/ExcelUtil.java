package utils;

import org.openqa.selenium.WebDriver;

/**
 * Stub Excel exporter — the original tool serialises a table from the page
 * into an .xlsx file. We log the call here so tests don't fail on missing
 * Apache POI and so deployers can plug in their own implementation.
 *
 * <p>Replace the body with your project's real implementation if you need
 * the export to actually run.</p>
 */
public final class ExcelUtil {

    private ExcelUtil() {}

    public static void exportTable(WebDriver driver, String sheetName, String maCH) {
        // Placeholder — replace with the real implementation if needed.
        System.out.println("[ExcelUtil] would export sheet=\"" + sheetName + "\" key=" + maCH);
    }
}
