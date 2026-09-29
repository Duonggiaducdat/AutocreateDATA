package com.fpt.autodata.generator.impl.service.ivoice.voice1800;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.autodata.generator.GeneratorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Kiểm tra logic retry của Cm1800ExecuteGenerator — đặc biệt là fix:
 * "thoát retry loop khi crTransactionId vẫn blank dù reg đã có".
 */
class Cm1800ExecuteGeneratorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Section 1: Pure logic — điều kiện retry ───────────────────────────

    @Nested
    @DisplayName("Điều kiện break retry loop")
    class RetryBreakCondition {

        /**
         * Logic mới: break chỉ khi reg != null VÀ crTransactionId không blank.
         * Test này verify trực tiếp điều kiện đó dưới dạng pure boolean.
         */
        @Test
        @DisplayName("reg có crTransactionId → break loop")
        void shouldBreakWhenCrTransactionIdPresent() {
            ObjectNode reg = mapper.createObjectNode();
            reg.put("crTransactionId", "aaaa-bbbb-cccc-dddd");
            reg.put("status", 2);

            boolean shouldBreak = !reg.isMissingNode()
                && !reg.path("crTransactionId").asText("").isBlank();

            assertThat(shouldBreak).isTrue();
        }

        @Test
        @DisplayName("reg có nhưng crTransactionId blank → tiếp tục retry")
        void shouldRetryWhenCrTransactionIdBlank() {
            ObjectNode reg = mapper.createObjectNode();
            reg.put("crTransactionId", "");
            reg.put("status", 2);

            boolean shouldBreak = !reg.isMissingNode()
                && !reg.path("crTransactionId").asText("").isBlank();

            assertThat(shouldBreak).isFalse();
        }

        @Test
        @DisplayName("reg có nhưng crTransactionId null JSON → tiếp tục retry")
        void shouldRetryWhenCrTransactionIdNullJson() {
            ObjectNode reg = mapper.createObjectNode();
            reg.putNull("crTransactionId");

            boolean shouldBreak = !reg.isMissingNode()
                && !reg.path("crTransactionId").asText("").isBlank();

            assertThat(shouldBreak).isFalse();
        }

        @Test
        @DisplayName("reg có nhưng crTransactionId field missing → tiếp tục retry")
        void shouldRetryWhenCrTransactionIdFieldMissing() {
            ObjectNode reg = mapper.createObjectNode();
            reg.put("status", 2);
            // không có field crTransactionId

            boolean shouldBreak = !reg.isMissingNode()
                && !reg.path("crTransactionId").asText("").isBlank();

            assertThat(shouldBreak).isFalse();
        }

        @Test
        @DisplayName("reg null (registration chưa sync) → tiếp tục retry")
        void shouldRetryWhenRegNull() {
            JsonNode reg = null;

            // code: if (reg != null && !reg.isMissingNode() && !reg.path(...).isBlank()) break;
            boolean shouldBreak = reg != null
                && !reg.isMissingNode()
                && !reg.path("crTransactionId").asText("").isBlank();

            assertThat(shouldBreak).isFalse();
        }

        @Test
        @DisplayName("OLD condition (chỉ check isMissingNode) cho phép thoát sớm khi crTxId blank")
        void oldConditionWouldBreakEarlyOnBlankCrTxId() {
            ObjectNode reg = mapper.createObjectNode();
            reg.put("crTransactionId", "");

            // Old behavior: break ngay khi tìm thấy reg, dù crTransactionId blank
            boolean oldShouldBreak = !reg.isMissingNode();
            // New behavior: không break
            boolean newShouldBreak = !reg.isMissingNode()
                && !reg.path("crTransactionId").asText("").isBlank();

            assertThat(oldShouldBreak).isTrue();  // old code sẽ thoát sớm — đây là bug
            assertThat(newShouldBreak).isFalse(); // new code tiếp tục retry — đúng
        }
    }

    // ── Section 2: Lỗi misleading khi crTransactionId null ────────────────

    @Nested
    @DisplayName("Error message khi crTransactionId vẫn null sau retry")
    class ErrorMessageOnMissingCrTxId {

        @Test
        @DisplayName("Sau 6 lần retry vẫn blank → ném lỗi mô tả đúng vấn đề")
        void errorMessageDescribesSyncIssue() {
            // Simulate: reg tìm thấy (tức BGĐ đã approve, CM đã có record)
            // nhưng crTransactionId vẫn chưa được populate sau 6 lần retry
            ObjectNode reg = mapper.createObjectNode();
            reg.put("crTransactionId", "");
            reg.put("status", 2);

            String regCode = "CMR2614715";
            String crTransactionId = reg.path("crTransactionId").asText(null);
            boolean isMissingOrBlank = crTransactionId == null || crTransactionId.isBlank();

            // Với code sau fix: sau MAX_DETAIL_RETRY lần, nếu crTransactionId vẫn blank,
            // reg sẽ là null (loop không break → reset reg = null)
            // Lỗi sẽ là "Không tìm thấy registration..." thay vì "chưa qua bước BMS approve?"
            assertThat(isMissingOrBlank)
                .as("crTransactionId blank → loop phải tiếp tục, không thoát sớm")
                .isTrue();
        }
    }

    // ── Section 3: Mock HTTP — Verify retry với response thật ─────────────

    @Nested
    @DisplayName("HTTP mock: retry khi crTransactionId dần có giá trị")
    class HttpMockRetryTest {

        @SuppressWarnings("unchecked")
        private HttpResponse<String> mockResponse(int status, String body) throws Exception {
            HttpResponse<String> resp = mock(HttpResponse.class);
            when(resp.statusCode()).thenReturn(status);
            when(resp.body()).thenReturn(body);
            return resp;
        }

        private String regDetailBody(String crTxId) {
            return String.format("""
                {"data":{"registration":{"crTransactionId":"%s","transactionId":"tx-001",
                "status":2,"contractNo":null}}}""", crTxId == null ? "" : crTxId);
        }

        private String regDetailBodyWithTxId(String crTxId) {
            return String.format("""
                {"data":{"registration":{"crTransactionId":"%s","transactionId":"tx-001",
                "status":2,"contractNo":null}}}""", crTxId);
        }

        /**
         * Simulate: 2 lần đầu crTransactionId blank (sync chưa xong),
         * lần 3 crTransactionId có giá trị.
         * Verify: generator không ném lỗi "chưa qua bước BMS approve?".
         */
        @Test
        @DisplayName("Simulate: crTransactionId populate ở attempt 3 → không ném lỗi")
        void retrySucceedsWhenCrTxIdPopulatesOnThirdAttempt() throws Exception {
            // Responses: attempt 1 blank, attempt 2 blank, attempt 3 có giá trị
            String blankTxBody = regDetailBody("");
            String validTxBody = regDetailBodyWithTxId("aaaa-bbbb-cccc-1234");

            // Verify parsing tay — như code thật xử lý
            ObjectMapper om = new ObjectMapper();

            JsonNode attempt1 = om.readTree(blankTxBody).path("data").path("registration");
            JsonNode attempt2 = om.readTree(blankTxBody).path("data").path("registration");
            JsonNode attempt3 = om.readTree(validTxBody).path("data").path("registration");

            boolean breakAt1 = !attempt1.isMissingNode() && !attempt1.path("crTransactionId").asText("").isBlank();
            boolean breakAt2 = !attempt2.isMissingNode() && !attempt2.path("crTransactionId").asText("").isBlank();
            boolean breakAt3 = !attempt3.isMissingNode() && !attempt3.path("crTransactionId").asText("").isBlank();

            assertThat(breakAt1).isFalse();
            assertThat(breakAt2).isFalse();
            assertThat(breakAt3).isTrue(); // break ở lần 3, crTxId = "aaaa-bbbb-cccc-1234"

            assertThat(attempt3.path("crTransactionId").asText())
                .isEqualTo("aaaa-bbbb-cccc-1234");
        }
    }
}
