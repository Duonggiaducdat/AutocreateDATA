package pages;

import base.BasePage;
import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class DetailPage extends BasePage {
    Actions actions;
    WebDriverWait wait;
    public DetailPage(WebDriver driver) {
        super(driver);
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        this.actions = new Actions(driver);
    }

    private final By tabRegist = By.xpath("//a[@id='tab-regist-header']");
    public By optionTypeService(String index){
        return By.xpath(String.format("//a[text()='%s ']",index));
    }
    private By inputNote = By.xpath("//textarea[@id='inputReason']");
    private By btnApprovalRegist = By.xpath("//button[@id='btnApprovalRegist']");
    private By btnConfirm = By.xpath("//button[text()='Xác nhận']");
    private By labelRequest = By.xpath("//a[text()='Trung tâm dữ liệu - Colocation ']//parent::td//following-sibling::td[1]");
    private By btnAddWork = By.xpath("//button[normalize-space(text())='Thêm công việc']");
    private By inputNameWork = By.xpath("//input[@id='nameInsertWork']");
    private By inputdescriptionWork = By.xpath("//textarea[@id='descriptionInsertWork']");
    private By editStage(int index) {
        return By.xpath("(//span[@onclick='StageSteperHorizonal.ShowEditStage(this)'])[" + index + "]");
    }
    private By note = By.xpath("//div[@class='editstage']/input[1]");
    private By submitStage(int index) {
        return By.xpath("(//span[@onclick='StageSteperHorizonal.SubmitEditStage(this)'])[" + index + "]");
    }
    private By btnUpdate = By.xpath("//button[text()='Cập nhật']");
    private By createAddWork = By.xpath("//button[normalize-space(text())='Tiếp tục']");
    private By btnHoanTat = By.xpath("//a[normalize-space(text())='Hoàn tất']");

    public By maYC(String index){
        return By.xpath(String.format("//a[text()='%s ']//parent::td//following-sibling::td[1]",index));
    }

    public void clickTabRegist(){
        waitForPageLoaded();
        click(tabRegist);
        waitForPageLoaded();
    }

    public void step2BookRack(){
        waitForPageLoaded();
        click(btnHoanTat);
    }

    public void approvalService(String text){
        clickTabRegist();
        waitForPageLoaded();
        sleep(2);
        String href = getElement(optionTypeService(text)).getAttribute("href");
        String refCode = href.substring(href.indexOf("RefCode=") + 8);
        String url = "https://ftms-stag.fpt.net/bms/RegistrationMagt_v12/ApprovalRegist?refCode=" + refCode;
        driver.get(url);
        waitForPageLoaded();
        click(btnApprovalRegist);
        sendKeys(inputNote,"Autonote");
        click(btnConfirm);
    }

    public String getMaYC(String text){
        for(int i = 0; i < 3; i++){
            waitForPageLoaded();
            clickTabRegist();
            refresh();
            clickTabRegist();
            String ma = getText(maYC(text));
            if(ma != null && !ma.trim().isEmpty()){
                return ma;
            }
            refresh();
            sleep(3);
        }
        return "";
    }

    public String getHref(By locator) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

        return wait.until(driver -> {
            try {
                return driver.findElement(locator)
                        .getAttribute("href");
            } catch (StaleElementReferenceException e) {
                return null;
            }
        });
    }

    private By maCH2 = By.xpath("//div[@id='follow-btn-container']");
    public String getMaCH(){
        return getElement(maCH2).getAttribute("data-opportunity");
    }

    public void addWorkBookRack() {
        waitForPageLoaded();
        scrollToBottom();
        sleep(1);
        click(btnAddWork);
        sendKeys(inputNameWork,"Bookrack");
        sendKeys(inputdescriptionWork,"Auto ghi chu");
        click(createAddWork);
    }

    /** Loop through stages 1..5 and write an auto note on each. */
    public void note() {
        for (int i = 1; i <= 5; i++) {
            waitForPageLoaded();
            click(editStage(i));
            sendKeys(note,"Auto ghi ch");
            click(submitStage(i));
            click(btnUpdate);
            sleep(3);
        }
    }
}
