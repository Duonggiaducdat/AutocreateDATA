package pages;

import base.BasePage;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.WebDriverWait;
import utils.DateUtil;

import java.time.Duration;

public class RequestIDC extends BasePage {
    Actions actions;
    WebDriverWait wait;
    public RequestIDC(WebDriver driver) {
        super(driver);
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        this.actions = new Actions(driver);
    }
    private By inputContract = By.xpath("//input[@placeholder='Người liên hệ']");
    private By inputPhone = By.xpath("//input[@placeholder='SĐT']");
    private By inputMail = By.xpath("//input[@placeholder='Email']");
    private By dropdownLocation = By.xpath("//mat-select[@id='mat-select-0']");
    public By choose (String index){
        return By.xpath(String.format("//span[text()='%s']",index));
    }
    private By dropDataCenter = By.xpath("//div[@id='mat-select-value-3']");
    public By chooseDataCenter (int index){
        return By.xpath(String.format("//div[@id='mat-select-2-panel']//mat-option[%d]",index));
    }
    private By inputDivce = By.xpath("//input[@placeholder='Kích thước thiết bị']");
    private By inputDegisn = By.xpath("//input[@placeholder='Chuẩn thiết kế tủ Rack']");
    private By inputInfor = By.xpath("//input[@placeholder='Thông tin cài đặt ban đầu']");
    private By inputInforU = By.xpath("//input[@placeholder='Vị trí U']");
    private By inputFromDay= By.xpath("//input[@placeholder='Từ ngày']");
    private By inputNextDay = By.xpath("//input[@placeholder='Tới ngày']");
    private By inputFromU= By.xpath("//input[@placeholder='Từ U']");
    private By inputNextU= By.xpath("//input[@placeholder='Đến U']");
    private By inputNextW= By.xpath("//input[@placeholder='Công suất theo hợp đồng/ phụ lục (W)']");
    private By inputInforIp = By.xpath("//input[@placeholder='Ip Address']");
    private By btnCreate = By.xpath("//div[text()='Tạo phiếu']");
    private By btnNext = By.xpath("//button[@id='btnCreateRegist']");
    private By labelType = By.xpath("//s18-tag[@class='ng-star-inserted']//strong");
    private By inputNameRam  = By.xpath("//div[@id='cdk-accordion-child-0']//input[@placeholder='Nhập tên chủng loại']");
    private By inputNameHdd  = By.xpath("//div[@id='cdk-accordion-child-1']//input[@placeholder='Nhập tên chủng loại']");
    private By inputCongSuat  = By.xpath("//input[@placeholder='Nhập Công suất theo hợp đồng/ phụ lục (W)']");
    private By input = By.xpath("//tr//td[3]//input[@placeholder='Nhập số lượng book']");
    private By addTu = By.xpath("//div[text()='Thêm tủ']");
    private By buttonDongY = By.xpath("//div[text()='Đồng ý']");
    private By buttonSendFTEL = By.xpath("//strong[text()='Gửi sang FTEL IDC']");
    private By open = By.xpath("//span[text()='BRXXXX-001']");
    private By inputNote = By.xpath("//textarea[@placeholder='Nhập vào nội dung ghi chú']");
    public By detailRack (String index){
        return By.xpath(String.format("//td[normalize-space(text())='%s']//preceding-sibling::td[1]",index));
    }

    public void createGeneralInformation() {
        waitForPageLoaded();
        sendKeys(inputContract, "Abc");
        sendKeys(inputPhone, "0976254369");
        sendKeys(inputMail, "shop@fpt.com");
        click(dropdownLocation);
        click(choose("FORNIX - HN02"));
        sleep(1);
        click(dropDataCenter);
        sleep(1);
        click(chooseDataCenter(1));
    }
    public void createRequest() {

        String type = getElement(labelType).getText();

        if (type.equals("Colocation")) {
            sendKeys(inputDivce, "2");
            sendKeys(inputInfor, "abc");
            sendKeys(inputInforU, "1");
            sendKeys(inputFromDay, "01/01/2026");
            sendKeys(inputNextDay, DateUtil.getToday());
            sendKeys(inputInforIp, "3");
            scrollIntoView(btnCreate);
            click(btnCreate);
            waitForPageLoaded();

        } else if (type.equals("Dedicated")) {

            sendKeys(inputDivce, "2");
            sendKeys(inputInfor, "abc");
            sendKeys(inputFromDay, "01/01/2026");
            sendKeys(inputNextDay, DateUtil.getToday());
            sendKeys(inputInforIp, "3");
            sendKeys(inputNameRam,"abc");
            scrollToBottom();
            sendKeys(inputNameHdd,"datc");
            scrollIntoView(btnCreate);
            click(btnCreate);
            waitForPageLoaded();

        } else {
            sendKeys(inputDegisn, "2");
            scrollIntoView(btnCreate);
            sendKeys(inputFromU, "1");
            sendKeys(inputNextU, "1");
            sendKeys(inputNextW, "30");
            scrollIntoView(btnCreate);
            click(btnCreate);
            waitForPageLoaded();
        }
    }


    public void createRequestDedicated() {
        sendKeys(inputDivce, "2");
        sendKeys(inputInfor, "abc");
        sendKeys(inputInforU, "1");
        sendKeys(inputFromDay, "01/01/2026");
        sendKeys(inputNextDay, DateUtil.getToday());
        sendKeys(inputInforIp, "3");
        scrollIntoView(btnCreate);
        click(btnCreate);
        waitForPageLoaded();
    }
    String filePath = System.getProperty("user.dir") + "\\file\\dummy.pdf";
    private By fileUpload = By.xpath("//s18-button[contains(.,'Chọn tệp')]/following-sibling::input[@type='file']");
    private By inputCalender = By.xpath("//input[@placeholder='Tới ngày']");
    public void createBookRack(String location) {
        waitForPageLoaded();
        uploadFile(filePath, fileUpload);
        click(dropdownLocation);
        click(choose(location));
        sendKeys(input,"1");
        click(addTu);
        click(open);
        sendKeys(inputCongSuat,"100");
        sendKeys(inputCalender,DateUtil.getPlusMonth());
        click(btnCreate);
    }
    public void sendIDC (String maCH){
        driver.get("https://ftms-stag.fpt.net/idc/V3/Booking/ApproveRequest");
        waitForPageLoaded();
        click(detailRack(maCH));
        scrollToBottom();
        sendKeys(inputNote,"Auto ghi chu");
        click(buttonDongY);
        sleep(5);
        scrollToBottom();
        sendKeys(inputNote,"Auto ghi chu");
        click(buttonDongY);
        sleep(5);
        scrollToBottom();
        sendKeys(inputNote,"Auto ghi chu");
        click(buttonSendFTEL);
        sleep(5);
    }
}
