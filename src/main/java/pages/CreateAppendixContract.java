package pages;

import base.BasePage;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import utils.DateUtil;

import java.time.Duration;

public class CreateAppendixContract extends BasePage {
        Actions actions;
        WebDriverWait wait;
        public CreateAppendixContract(WebDriver driver) {
            super(driver);
            this.wait = new WebDriverWait(driver, Duration.ofSeconds(30));
            this.actions = new Actions(driver);
        }

        private By btnEnd = By.xpath("//div[@id='listStage-view-top']/div[5]");
        private By btnRequest = By.xpath("//span[text()='Yêu cầu tạo hợp đồng']");
        private By btntnConfirmCreate = By.xpath("//div[@class='swal2-actions']//button[text()='Xác nhận']");
        private By btnNext = By.xpath("//button[@id='btnCreateRegist']");
        private By dropLocator = By.xpath("//span[@aria-controls='location_listbox']");
        private By locator =By.xpath("//ul[@id='location_listbox']//li[text()='Thành phố Hà Nội']");
        private By inputOnePayment = By.xpath("//input[@id='feeContract']/preceding-sibling::input");
        private By inputOnePayment1 = By.xpath("//input[@id='feeContract']");
        private By inputMonthlyFee = By.xpath("//input[@id='monthlyFeeContract']/preceding-sibling::input");
        private By inputMonthlyFee1 = By.xpath("//input[@id='monthlyFeeContract']");
        private By btnAddContract = By.xpath("//button[@id='addContactRow']");
        private By inputContractName = By.xpath("//input[@id='inputContactName']");
        private By inputCity = By.xpath("//input[@name='inputContactCity_input']");
        private By inputDistrict = By.xpath("//input[@name='inputContactDistrict_input']");
        private By inputWard = By.xpath("//input[@name='inputContactWard_input']");
        private By inputAddress = By.xpath("//input[@name='inputContactAddress']");
        private By inputContactPhone = By.xpath("//input[@name='inputContactPhone']");
        private By inputContactEmail = By.xpath("//input[@name='inputContactEmail']");
        private By btnAddContactInfo = By.xpath("//button[@id='addContactInfo']");
        private By btnAddTechRow = By.xpath("//button[@id='addTechRow']");
        private By inputTechContact = By.xpath("//input[@name='inputTechContact']");
        private By inputTechNumber = By.xpath("//input[@name='inputTechNumber']");
        private By inputTechEmail = By.xpath("//input[@name='inputTechEmail']");
        private By btnAddTechInfo= By.xpath("//button[@id='addTechInfo']");
        private By btnAddDetail = By.xpath("//div[@id='showButtonCallTichHop']");
        private By btnStep3 = By.xpath("//a[@id='tabServiceDetailFinish']");
        private final By tabRegist = By.xpath("//a[@id='tab-regist-header']");
        private By btnAddHD = By.xpath("//a[@id='btnAddYCTHD']");

        public By optionTypeService(String index){
            return By.xpath(String.format("//ul[@id='radioGroupService']//label[text()='%s']",index));
        }
        public void request(String type){
            if (isDisplayed(tabRegist))
            {
                click(tabRegist);
                click(btnAddHD);
                click(optionTypeService(type));
                click(btntnConfirmCreate);
                click(btntnConfirmCreate);
            }else {
                hover(btnEnd);
                click(btnRequest);
                click(optionTypeService(type));
                click(btntnConfirmCreate);
                click(btntnConfirmCreate);
            }

        }
        public void createStep1(){
            waitForPageLoaded();
            click(btnNext);
        }
        public void createStep2(){
            click(dropLocator);
            click(locator);
            click(inputOnePayment);
            getElement(inputOnePayment1).sendKeys("500000");
            click(inputMonthlyFee);
            getElement(inputMonthlyFee1).sendKeys("500000");
            scrollToBottom();
            sleep(1);
            click(btnAddContract);
            sendKeys(inputContractName,"Nguyễn Văn A");
            sendKeys(inputCity,"Thành phố Hồ Chí Minh");
            clickBody();
            sleep(1);
            sendKeys(inputDistrict,"Phường Tân Thuận");
            clickBody();
            sleep(1);
//            sendKeys(inputWard,"Phường 15");
            sendKeys(inputAddress,"123");
            sendKeys(inputContactPhone,"0976336351");
            sendKeys(inputContactEmail,"admin@fpt.com");
            click(btnAddContactInfo);
            scrollIntoView(btnAddTechRow);
            click(btnAddTechRow);
            sendKeys(inputTechContact,"Ky thuat");
            sendKeys(inputTechNumber,"0976258965");
            sendKeys(inputTechEmail,"kythuat@fpt.com");
            click(btnAddTechInfo);
            click(btnAddDetail);
            click(btntnConfirmCreate);
        }

        public void createStep3(){
            waitForPageLoaded();
            hideChatbot();
            sleep(3);
            click(btnStep3);
            sleep(3);
            click(btnNext);
            waitForPageLoaded();
        }

}
