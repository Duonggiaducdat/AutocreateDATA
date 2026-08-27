package pages;

import base.BasePage;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * BMS v2 (Ant Design UI, https://ftms-stag.fpt.net/bmsv2/...) page object cho luồng
 * cập nhật "VÒNG ĐỜI CƠ HỘI" — 5 giai đoạn Báo giá (Khảo sát/Tiếp cận → Demo/Tư vấn →
 * Chào giá → Đàm phán → Kết thúc/Yêu cầu tạo hợp đồng) qua modal "Chuyển giai đoạn".
 *
 * Tách riêng khỏi {@link LoginPage}/{@link DetailPage}/{@link CreatePage} (build cho BMS
 * v1 — Kendo UI, url /bms/OppsMagt/..., selector kiểu txtNameOpp/serviceGroup_%d_listbox)
 * để không đụng vào pipeline {@code FullFlowGenerator} hiện có đang chạy trên v1.
 *
 * Danh sách giai đoạn hợp lệ để chuyển đến PHỤ THUỘC giai đoạn hiện tại của cơ hội (server
 * trả về option khác nhau tuỳ trạng thái) — vì vậy {@link #advanceStage} luôn đọc lại
 * option thật trên UI mỗi lần gọi thay vì giả định trước một danh sách cố định.
 */
public class OpportunityStageV2 extends BasePage {

    private static final String BASE_URL = "https://ftms-stag.fpt.net";

    public OpportunityStageV2(WebDriver driver) {
        super(driver);
    }

    // Dùng contains(., ...) (toàn bộ text descendant) thay vì contains(text(), ...) (chỉ text
    // node con trực tiếp) — nút Ant Design thường bọc label trong <span> con, contains(text(),...)
    // sẽ không khớp trong trường hợp đó.
    // LƯU Ý: KHÔNG dùng translate(...) để so khớp không phân biệt hoa/thường cho text có dấu
    // tiếng Việt — translate() theo từng ký tự, chỉ cần thiếu 1 ký tự có dấu trong bảng chuyển
    // (VD "Ế") là toàn bộ điều kiện không bao giờ khớp, mà lỗi này KHÔNG hiện rõ (Selenium chỉ
    // báo "timeout chờ clickable" như thể nút chưa sẵn sàng, chứ không báo "không tìm thấy").
    // Đã xác nhận thực tế: nút "TIẾP THEO" hiển thị rõ trên màn hình nhưng bị treo wait ~30s do
    // đúng lỗi này. Khớp thẳng text hoa/thường như hiển thị thật, xác nhận qua screenshot.
    private final By ftiBmsTile = By.xpath("//*[contains(.,'FTI-BMS') and not(contains(.,'FTI-BMSv1'))]");
    private final By otpOption = By.xpath("//p[contains(.,'Sử dụng mật khẩu một lần')]");
    private final By emailInput = By.xpath("//input[contains(translate(@placeholder,'EMAIL','email'),'email')]");
    private final By nextBtn = By.xpath("//button[contains(.,'TIẾP THEO') or contains(.,'Tiếp theo')]");
    private final By otpInput = By.xpath("//input[contains(@placeholder,'OTP') or contains(@placeholder,'otp') or contains(@placeholder,'xác thực') or contains(@placeholder,'Xác thực')]");
    private final By loginBtn = By.xpath("//button[contains(.,'ĐĂNG NHẬP') or contains(.,'Đăng nhập')]");

    private final By searchBox = By.xpath("//input[contains(@placeholder,'Nhập thông tin cần tìm')]");
    private final By chuyenGiaiDoanBtn = By.xpath("//button[contains(.,'Chuyển giai đoạn')]");
    private final By modal = By.xpath("//*[contains(@class,'ant-modal') and contains(.,'Chuyển giai đoạn')]");
    private final By stageSelectInput = By.id("targetStageId");
    private final By planEndDateInput = By.id("planEndDate");
    private final By noteTextarea = By.id("note");
    private final By saveBtn = By.xpath("//button[contains(.,'Lưu và chuyển giai đoạn')]");
    private final By nextMonthBtn = By.xpath("//*[contains(@class,'ant-picker-header-next-btn')]");
    private final By dropdownOptions = By.xpath("//*[contains(@class,'ant-select-item-option-content')]");

    /**
     * Đăng nhập BMS v2 qua OTP tĩnh. Xử lý linh hoạt 3 trạng thái có thể gặp: đã ở thẳng
     * app (session còn hiệu lực), ở portal chọn app (cần bấm tile "FTI-BMS"), hoặc ở màn
     * hình xác thực (cần điền email + OTP) — lặp tối đa 6 lần cho tới khi vào được app.
     */
    public void login(String username, String otpCode) {
        // Lượt SSO đầu tiên trên môi trường staging này gần như luôn "nảy" (redirect ticket
        // chưa kịp trao đổi xong) — xác nhận thực tế qua Playwright script cùng dự án, script
        // đó luôn fail lượt 1 rồi thành công lượt 2. Bọc retry 3 lần, mỗi lần đi lại từ đầu.
        RuntimeException lastErr = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                attemptLogin(username, otpCode);
                return;
            } catch (RuntimeException e) {
                lastErr = e;
                log.warn("Login lần {}/3 thất bại, thử lại: {}", attempt, e.getMessage());
            }
        }
        throw lastErr;
    }

    private void attemptLogin(String username, String otpCode) {
        openUrl(BASE_URL + "/bmsv2/opportunity");
        for (int i = 0; i < 6; i++) {
            if (isDisplayed(searchBox)) return;
            if (isDisplayed(ftiBmsTile)) {
                click(ftiBmsTile);
                waitForPageLoaded();
                sleep(1);
                continue;
            }
            if (isDisplayed(otpOption) || isDisplayed(emailInput)) {
                if (isDisplayed(otpOption)) clickSlow(otpOption, 30);
                sendKeys(emailInput, username);
                clickSlow(nextBtn, 30);
                sendKeys(otpInput, otpCode);
                clickSlow(loginBtn, 30);
                waitForPageLoaded();
                sleep(1);
                openUrl(BASE_URL + "/bmsv2/opportunity");
                continue;
            }
            sleep(2);
        }
        waitForVisibility(searchBox);
    }

    /**
     * Click phần tử ĐẦU TIÊN thực sự hiển thị+enabled khớp locator — không dùng
     * {@code ExpectedConditions.elementToBeClickable(By)} vì nó luôn lấy match ĐẦU TIÊN theo
     * DOM order kể cả khi ẩn (VD template/tooltip ẩn cùng label "Tiếp theo" nằm trước nút
     * thật trong DOM) và treo mãi chờ nó "clickable" trong khi nút thật vẫn đứng yên bên
     * dưới — xác nhận qua thực tế: nút hiện rõ trong screenshot lúc lỗi nhưng wait vẫn timeout
     * dù tăng lên 30s. Tự poll danh sách match, chọn phần tử displayed+enabled đầu tiên.
     */
    private void clickSlow(By locator, int seconds) {
        WebElement el = new WebDriverWait(driver, Duration.ofSeconds(seconds)).until(d -> {
            for (WebElement candidate : d.findElements(locator)) {
                try {
                    if (candidate.isDisplayed() && candidate.isEnabled()) return candidate;
                } catch (org.openqa.selenium.StaleElementReferenceException ignored) { /* re-poll */ }
            }
            return null;
        });
        try {
            el.click();
        } catch (Exception e) {
            ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].click();", el);
        }
    }

    /** Tìm cơ hội theo mã và mở trang chi tiết (điều hướng qua danh sách + click link — đi
     *  thẳng URL /opportunity/detail/{code} có thể bị 403 do SPA chưa thiết lập session context). */
    public void gotoOpportunity(String chCode) {
        openUrl(BASE_URL + "/bmsv2/opportunity");
        waitForVisibility(searchBox);
        sendKeys(searchBox, chCode);
        sleep(2);
        By link = By.xpath(String.format("//a[contains(.,'%s')]", chCode));
        waitForVisibility(link);
        click(link);
        waitForPageLoaded();
        sleep(1);
    }

    /**
     * Chuyển cơ hội đang mở sang {@code targetStage} qua modal "Chuyển giai đoạn".
     *
     * @param targetStage  tên giai đoạn đích, khớp nguyên văn 1 trong các option đang hiển
     *                     thị trên dropdown (VD "Demo/Tư vấn", "Chào giá", "Đàm phán"...)
     * @param planEndDate  ngày dự kiến kết thúc giai đoạn, định dạng ISO yyyy-MM-dd — null/rỗng
     *                     = bỏ qua nếu field đã có sẵn giá trị mặc định
     * @param note         ghi chú — null/rỗng = bỏ qua (field không bắt buộc)
     * @return danh sách option thực tế đọc được trên dropdown (để caller biết cần thử lại
     *         với giai đoạn nào nếu {@code targetStage} không khớp)
     */
    public java.util.List<String> advanceStage(String targetStage, String planEndDate, String note) {
        click(chuyenGiaiDoanBtn);
        waitForVisibility(modal);

        click(stageSelectInput);
        sleep(1);
        java.util.List<String> options = getElements(dropdownOptions).stream()
                .map(el -> el.getText().trim())
                .filter(t -> !t.isEmpty())
                .toList();

        if (!options.contains(targetStage)) {
            sendKeysEscape();
            throw new IllegalStateException(
                    "Giai đoạn \"" + targetStage + "\" không có trong danh sách chuyển được từ trạng thái hiện tại. "
                            + "Các lựa chọn khả dụng: " + options);
        }
        By optionLocator = By.xpath(String.format(
                "//*[contains(@class,'ant-select-item-option-content')][normalize-space(.)='%s']", targetStage));
        clickWithRetry(optionLocator, 3);
        sleep(1);

        if (planEndDate != null && !planEndDate.isBlank()) {
            pickDate(planEndDate);
        }

        if (note != null && !note.isBlank()) {
            sendKeys(noteTextarea, note);
        }

        click(saveBtn);
        sleep(2);
        return options;
    }

    private void pickDate(String isoDate) {
        click(planEndDateInput);
        sleep(1);
        By cell = By.xpath(String.format("//*[contains(@class,'ant-picker-cell') and @title='%s']", isoDate));
        for (int i = 0; i < 14; i++) {
            if (isDisplayed(cell)) {
                String cls = getAttribute(cell, "class");
                if (cls == null || !cls.contains("disabled")) {
                    click(cell);
                    return;
                }
            }
            click(nextMonthBtn);
            sleep(1);
        }
        throw new IllegalStateException("Không tìm thấy ô lịch cho ngày " + isoDate);
    }

    private void sendKeysEscape() {
        driver.switchTo().activeElement().sendKeys(org.openqa.selenium.Keys.ESCAPE);
    }

    /** Hôm nay + {@code daysAhead} ngày, định dạng ISO yyyy-MM-dd cho {@link #advanceStage}. */
    public static String isoDatePlusDays(int daysAhead) {
        return LocalDate.now().plusDays(daysAhead).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
