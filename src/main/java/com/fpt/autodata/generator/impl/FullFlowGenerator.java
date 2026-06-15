package com.fpt.autodata.generator.impl;

import com.fpt.autodata.generator.GenerationResult;
import com.fpt.autodata.generator.ParamSpec;
import com.fpt.autodata.generator.Params;
import com.fpt.autodata.service.automation.FtmsAutomation;
import models.ServiceGroup;
import org.openqa.selenium.By;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pages.BaoGia;
import pages.CreateAppendixContract;
import pages.CreatePage;
import pages.DetailPage;
import pages.LoginPage;
import pages.LogoutPage;
import pages.RequestCM;
import pages.RequestIDC;
import pages.ReviewPage;
import utils.ExcelUtil;
import utils.JsonUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * End-to-end orchestrator: login → create-order → review → note → quote → loop → export.
 * stopAfter short-circuits the pipeline at the chosen phase.
 */
@Component
public class FullFlowGenerator extends AbstractGenerator {

    private static final By MA_CH  = By.xpath("//tbody[@role='rowgroup']//tr[1]//descendant::a[contains(text(),'CH0008')]");
    private static final By MA_CH1 = By.xpath("//a[contains(text(),'CH0008')]");

    public static final List<String> PHASES = List.of(
            "login", "create-order", "review", "note", "quote", "loop", "export");

    private final String reviewerUser;
    private final String reviewerPass;

    public FullFlowGenerator(FtmsAutomation automation,
                             @Value("${autodata.defaults.reviewer-username:reviewer01@fpt.com}") String reviewerUser,
                             @Value("${autodata.defaults.reviewer-password:REDACTED_OTP_CODE}") String reviewerPass) {
        super(automation);
        this.reviewerUser = reviewerUser;
        this.reviewerPass = reviewerPass;
    }

    @Override public String type()        { return "full-flow"; }
    @Override public String displayName() { return "Master Flow"; }
    @Override public String category()    { return "Master"; }
    @Override public String description() { return "Dang nhap -> Len co hoi -> Danh gia -> Ghi chu -> Bao gia -> Loop -> Xuat Excel."; }
    @Override public String signature()   { return "testCreate(serviceGroup, customerName, options, name, selectedServices, optionsService, userSale, passSale, truongPhong, banGiamDoc, stopAfter)"; }

    @Override
    public List<ParamSpec> params() {
        ServiceGroup cfg = loadServices();
        List<String> groups = cfg.getGroup() == null ? List.of() : new ArrayList<>(cfg.getGroup().keySet());

        return List.of(
                ParamSpec.enumOf("serviceGroup", groups)
                        .required().defaultValue(groups.isEmpty() ? null : groups.get(0)),
                ParamSpec.text("customerName").required().placeholder("name / ma KH / MST / CCCD"),
                ParamSpec.text("name").required().placeholder("ten co hoi"),
                ParamSpec.multiEnum("selectedServices", List.of())
                        .hint("driven by serviceGroup").defaultValue(List.of()),
                ParamSpec.text("optionsService")
                        .hint("csv override - blank = same as selectedServices").defaultValue(""),
                ParamSpec.text("options")
                        .hint("rack qty - only for Trung tam du lieu")
                        .defaultValue("Nhieu hon hoac bang 1 tu"),
                ParamSpec.text("userNameSale").required().defaultValue("salesale@fpt.com"),
                ParamSpec.password("passSale").required().defaultValue("REDACTED_OTP_CODE"),
                ParamSpec.text("truongPhong").required().defaultValue("Tpp03"),
                ParamSpec.text("banGiamDoc").required().defaultValue("Bgd01"),
                ParamSpec.enumOf("stopAfter", PHASES)
                        .hint("dung pipeline sau phase nay").defaultValue("export")
        );
    }

    @Override
    public GenerationResult generate(Map<String, Object> p) {
        String serviceGroup     = Params.requireString(p, "serviceGroup");
        String customer         = Params.requireString(p, "customerName");
        String name             = Params.requireString(p, "name");
        List<String> selected   = Params.optList(p, "selectedServices", List.of());
        String optionsService   = Params.optString(p, "optionsService", "");
        String options          = Params.optString(p, "options", "n/a");
        String userSale         = Params.requireString(p, "userNameSale");
        String passSale         = Params.requireString(p, "passSale");
        String truongPhong      = Params.requireString(p, "truongPhong");
        String banGD            = Params.requireString(p, "banGiamDoc");
        String stopAfter        = Params.optString(p, "stopAfter", "export").toLowerCase();

        int stopIdx = PHASES.indexOf(stopAfter);
        if (stopIdx < 0) stopIdx = PHASES.size() - 1;

        long t0 = System.currentTimeMillis();
        List<String> log = newLog();

        ServiceGroup cfg = loadServices();
        ServiceGroup.ServiceItem item = cfg.getGroup() == null ? null : cfg.getGroup().get(serviceGroup);
        List<String> groupServices = item != null && item.getServices() != null
                ? item.getServices() : List.of();
        boolean allowsRack = item != null && item.isAllowsRackOption();

        List<String> createServices = (selected != null && !selected.isEmpty())
                ? selected : groupServices;

        List<String> loopServices = (optionsService == null || optionsService.isBlank())
                ? createServices
                : Arrays.stream(optionsService.split("\\s*,\\s*")).filter(s -> !s.isBlank()).toList();

        String effectiveOptions = allowsRack ? options : "n/a";

        trace(log, "START - group=\"" + serviceGroup + "\"");
        trace(log, "  createServices=" + createServices);
        trace(log, "  loopServices=" + loopServices);
        trace(log, "  stopAfter=" + stopAfter);

        List<Map<String, Object>> ycResults = new ArrayList<>();
        String[] maChHolder = new String[1];
        String[] excelExport = new String[1];

        final int stopIdxFinal = stopIdx;

        automation.withDriver(driver -> {
            LoginPage login            = new LoginPage(driver);
            CreatePage create          = new CreatePage(driver);
            LogoutPage logout          = new LogoutPage(driver);
            ReviewPage review          = new ReviewPage(driver);
            BaoGia    baoGia           = new BaoGia(driver);
            CreateAppendixContract apx = new CreateAppendixContract(driver);
            RequestIDC idc             = new RequestIDC(driver);
            DetailPage detail          = new DetailPage(driver);
            RequestCM cm               = new RequestCM(driver);

            // 1. login
            login.login(userSale, passSale);
            trace(log, "ok login");
            if (stopIdxFinal < PHASES.indexOf("create-order")) return;

            // 2. create-order
            create.createStep1(serviceGroup, createServices, effectiveOptions, name);
            create.createStep2(customer);
            create.createStep3(truongPhong, banGD);
            String href = detail.getHref(MA_CH);
            trace(log, "ok create-order - href=" + href);
            if (stopIdxFinal < PHASES.indexOf("review")) return;

            // 3. review
            driver.get(href);
            logout.Logout();
            login.login(reviewerUser, reviewerPass);
            driver.get(href);
            review.review();
            String href1 = detail.getHref(MA_CH1);
            driver.get(href1);
            trace(log, "ok review - href1=" + href1);
            if (stopIdxFinal < PHASES.indexOf("note")) return;

            // 4. note
            detail.note();
            trace(log, "ok note");
            if (stopIdxFinal < PHASES.indexOf("quote")) return;

            // 5. quote
            baoGia.addQuote();
            trace(log, "ok quote");
            if (stopIdxFinal < PHASES.indexOf("loop")) return;

            // 6. loop
            for (String service : loopServices) {
                if ("Rack".equals(service)
                        && "Nhieu hon hoac bang 1 tu".equals(effectiveOptions)
                        && allowsRack) {
                    driver.get(href1);
                    String maCH = detail.getMaCH();
                    maChHolder[0] = maCH;
                    detail.addWorkBookRack();
                    idc.createBookRack("FORNIX - HN02");
                    detail.step2BookRack();
                    idc.sendIDC(maCH);
                    trace(log, "  - " + service + " (rack)");
                    continue;
                }
                driver.get(href1);
                apx.request(serviceGroup + " - " + service);
                apx.createStep1();
                apx.createStep2();
                idc.createGeneralInformation();
                idc.createRequest();
                apx.createStep3();
                detail.approvalService(serviceGroup + " - " + service);
                driver.get(href1);
                driver.navigate().refresh();
                String maYC = detail.getMaYC(serviceGroup + " - " + service);
                ycResults.add(Map.of("service", service, "maYC", maYC));
                cm.request(maYC);
                trace(log, "  - " + service + " - maYC=" + maYC);
            }
            trace(log, "ok loop");
            if (stopIdxFinal < PHASES.indexOf("export")) return;

            // 7. export
            driver.get(href1);
            detail.clickTabRegist();
            String maCH = detail.getMaCH();
            if (maChHolder[0] == null) maChHolder[0] = maCH;
            ExcelUtil.exportTable(driver, "ThongtinHD", maCH);
            excelExport[0] = "ThongtinHD_" + maCH + ".xlsx";
            trace(log, "ok export");
        });

        trace(log, "pipeline finished at phase=\"" + stopAfter + "\"");
        return result()
                .put("maCH", maChHolder[0])
                .put("createServices", createServices)
                .put("loopServices", loopServices)
                .put("stoppedAfter", stopAfter)
                .put("results", ycResults)
                .put("excelExport", excelExport[0])
                .log(log)
                .durationMs(System.currentTimeMillis() - t0)
                .build();
    }

    private ServiceGroup loadServices() {
        try { return JsonUtil.loadJsonData("Services.json", ServiceGroup.class); }
        catch (RuntimeException e) { return new ServiceGroup(); }
    }
}
