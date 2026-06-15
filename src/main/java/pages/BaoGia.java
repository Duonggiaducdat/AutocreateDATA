package pages;

import base.BasePage;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import utils.DateUtil;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

public class BaoGia extends BasePage {
    Actions actions;
    WebDriverWait wait;
    public BaoGia(WebDriver driver) {
        super(driver);
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        this.actions = new Actions(driver);
    }
    private By header = By.id("tab-documment-header");
    private By Quote = By.id("tab-quote-header");
    private By btnQuote = By.id("btnAddOppFileQuota");
    private By options = By.xpath("//ul[@id='serviceDropdownQuota_1_listbox']//li");
    private By dropDown(int index){
        return By.xpath(String.format("//span[@aria-controls='serviceDropdownQuota_%d_listbox']",index));
    }
    private By serviceOption(int a, int b) {
        return By.xpath(String.format("//ul[@id='serviceDropdownQuota_%d_listbox']//li[@data-offset-index='%d']",a,b));
    }
    private By priceInput(int index) {
        return By.xpath(String.format("//input[@id='priceInput_%d']//preceding-sibling::input[@placeholder='Giá trị (VND) *']",index));
    }
    private By addColumn = By.id("addServiceButton");
    private By fileUpload = By.xpath("//span[text()='Đính kèm file']//preceding-sibling::input[@id='fileUpload']");
    private By btnConfirm = By.xpath("//button[@onclick='submitQuotation()']");
    private By btnConfirmCreate = By.xpath("//button[@class='swal2-confirm swal2-styled']");

    private By btnClose = By.xpath("//button[text()='ĐÓNG']");

    public By optionTypeService(int index){
        return By.xpath(String.format("//ul[@id='radioGroupService']/li[%d]/input",index));
    }
    public void addQuote() {
        waitForPageLoaded();

        // Tab tài liệu có thể chưa xuất hiện ngay sau khi review submit
        // → refresh tối đa 3 lần cho đến khi tab hiển thị
        for (int i = 0; i < 3; i++) {
            if (isDisplayed(header)) break;
            System.out.println("addQuote retry " + (i + 1) + "/3: tab-documment chưa hiển thị, refresh...");
            refresh();
        }

        click(header);
        click(Quote);
        click(btnQuote);
        int a = 0;
        for (int i = 1; i <= getElements(options).size(); i++) {
            // Click dropdown, kiểm tra option xuất hiện — nếu chưa thì click lại
            for (int retry = 0; retry < 3; retry++) {
                click(dropDown(i));
                sleep(1);
                if (isDisplayed(serviceOption(i, a))) break;
                log.warn("dropDown({}) chưa mở, retry {}/3", i, retry + 1);
            }
            clickWithRetry(serviceOption(i, a), 3);
            sendKeys(priceInput(i), "1");
            a++;
            if (i < getElements(options).size()) {
                click(addColumn);
            }
        }
        String filePath = resolveDummyPdfPath();
        log.info("Uploading file: {}", filePath);
        uploadFile(filePath, fileUpload);
        sleep(1);
        click(btnConfirm);
        click(btnConfirmCreate);
        sleep(5);
        click(btnClose);
        sleep(5);
    }

    /**
     * Resolve absolute path tới file dummy.pdf — robust với mọi cách start backend.
     *
     * Thứ tự ưu tiên:
     *   1. Override qua system property: -Dautodata.dummy.pdf=/path/to/file.pdf
     *   2. Classpath resource (đóng gói trong JAR) → extract ra temp folder
     *   3. Fallback: <project_root>/file/dummy.pdf
     *
     * Cách (2) hoạt động cho cả 'mvn spring-boot:run' lẫn 'java -jar', không phụ
     * thuộc working directory. File extract được cache ở temp folder của OS,
     * lần upload sau dùng lại cùng file.
     */
    private String resolveDummyPdfPath() {
        // (1) System property override
        String override = System.getProperty("autodata.dummy.pdf");
        if (override != null && !override.isBlank()) {
            Path p = Paths.get(override).toAbsolutePath();
            if (Files.exists(p)) return p.toString();
            log.warn("autodata.dummy.pdf override không tồn tại: {}", p);
        }

        // (2) Classpath resource → extract ra temp
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("file/dummy.pdf")) {
            if (in != null) {
                Path tmp = Paths.get(System.getProperty("java.io.tmpdir"),
                        "autodata-dummy.pdf");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                return tmp.toAbsolutePath().toString();
            }
        } catch (Exception e) {
            log.warn("Không extract được dummy.pdf từ classpath: {}", e.getMessage());
        }

        // (3) Fallback — đi ngược lên tìm folder 'file/' (xử lý cả khi user.dir
        // = project root hoặc backend/)
        Path base = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : List.of(
                base.resolve("file/dummy.pdf"),
                base.resolve("../file/dummy.pdf"),
                base.resolve("../../file/dummy.pdf"))) {
            Path normalized = candidate.normalize();
            if (Files.exists(normalized)) return normalized.toString();
        }

        throw new IllegalStateException(
                "Không tìm thấy dummy.pdf. Đặt file tại src/main/resources/file/dummy.pdf " +
                "hoặc set -Dautodata.dummy.pdf=<absolute_path>");
    }
}
