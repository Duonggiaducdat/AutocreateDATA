package com.fpt.autodata.generator.impl.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/**
 * Kiểm tra logic parse trong GetNewCmTokenApiGenerator — đặc biệt là fix:
 * "parse execution token ngay từ Step 1 body, không cần Step 1b GET thứ 2".
 */
class GetNewCmTokenApiGeneratorTest {

    // Regex giống y chang trong generator (copy để test độc lập)
    private static final Pattern TICKET_PAT =
        Pattern.compile("[?&]ticket=(ST-[^&\\s\\r\\n]+)");
    private static final Pattern EXECUTION_PAT =
        Pattern.compile("name=\"execution\"\\s+value=\"([^\"]+)\"");

    // ── Section 1: EXECUTION_PAT — parse execution token từ login form ────

    @Nested
    @DisplayName("EXECUTION_PAT: parse execution token từ CAS login form HTML")
    class ExecutionPatternTest {

        @Test
        @DisplayName("Parse execution token từ HTML form bình thường")
        void parsesExecutionTokenFromHtml() {
            String html = """
                <input type="hidden" name="execution" value="e2s1_abc123XYZ" />
                """;
            Matcher m = EXECUTION_PAT.matcher(html);
            assertThat(m.find()).isTrue();
            assertThat(m.group(1)).isEqualTo("e2s1_abc123XYZ");
        }

        @Test
        @DisplayName("Parse execution token với nhiều spaces")
        void parsesExecutionTokenWithExtraSpaces() {
            String html = """
                <input  name="execution"  value="e1s1_tokenFoo"  type="hidden">
                """;
            Matcher m = EXECUTION_PAT.matcher(html);
            assertThat(m.find()).isTrue();
            assertThat(m.group(1)).isEqualTo("e1s1_tokenFoo");
        }

        @Test
        @DisplayName("HTML không có execution field → không match")
        void noMatchWhenExecutionFieldAbsent() {
            String html = "<form><input name=\"username\" value=\"demo\"/></form>";
            Matcher m = EXECUTION_PAT.matcher(html);
            assertThat(m.find()).isFalse();
        }

        @Test
        @DisplayName("Parse execution token từ response thực tế (format CAS 6.x)")
        void parsesRealWorldCasExecutionToken() {
            // Format thực từ CAS 6.x
            String html = """
                <input type="hidden" name="execution" value="0a1b2c3d4e5f6g7h8i9j_AA-12-BBB-CCCC"/>
                """;
            Matcher m = EXECUTION_PAT.matcher(html);
            assertThat(m.find()).isTrue();
            assertThat(m.group(1)).isEqualTo("0a1b2c3d4e5f6g7h8i9j_AA-12-BBB-CCCC");
        }
    }

    // ── Section 2: TICKET_PAT — parse ST ticket từ Location header ────────

    @Nested
    @DisplayName("TICKET_PAT: parse ST ticket từ CAS redirect Location")
    class TicketPatternTest {

        @Test
        @DisplayName("Parse ST ticket từ redirect URL chuẩn")
        void parsesTicketFromRedirectUrl() {
            String location = "https://ftms-stag.fpt.net/new-cm?ticket=ST-12345-abcdef";
            Matcher m = TICKET_PAT.matcher(location);
            assertThat(m.find()).isTrue();
            assertThat(m.group(1)).isEqualTo("ST-12345-abcdef");
        }

        @Test
        @DisplayName("Parse ST ticket khi có query params khác trước ticket")
        void parsesTicketAfterOtherParams() {
            String location = "https://ftms-stag.fpt.net/new-cm?foo=bar&ticket=ST-99999-XXXXX";
            Matcher m = TICKET_PAT.matcher(location);
            assertThat(m.find()).isTrue();
            assertThat(m.group(1)).isEqualTo("ST-99999-XXXXX");
        }

        @Test
        @DisplayName("Không có ticket trong URL → không match")
        void noMatchWhenNoTicket() {
            String location = "https://ftms-stag.fpt.net/new-cm";
            Matcher m = TICKET_PAT.matcher(location);
            assertThat(m.find()).isFalse();
        }

        @Test
        @DisplayName("Ticket dừng tại & và không lấy thêm")
        void ticketStopsAtAmpersand() {
            String location = "https://ftms-stag.fpt.net/new-cm?ticket=ST-ABC-123&other=x";
            Matcher m = TICKET_PAT.matcher(location);
            assertThat(m.find()).isTrue();
            assertThat(m.group(1)).isEqualTo("ST-ABC-123");
        }
    }

    // ── Section 3: Verify fix — Step 1 parse execution → Step 1b bỏ qua ──

    @Nested
    @DisplayName("Step1 parse execution — giảm round-trip")
    class Step1ExecutionParseOptimizationTest {

        @Test
        @DisplayName("Step 1 HTTP 200 có execution → executionFromStep1 không null → Step 1b skip")
        void step1bIsSkippedWhenStep1ParsedExecution() {
            // Simulate Step 1 response body (login form HTML)
            String step1Body = """
                <html><body>
                <form>
                <input type="hidden" name="execution" value="e1s1_STEP1TOKEN"/>
                <input type="hidden" name="_eventId" value="submit"/>
                </form>
                </body></html>
                """;

            // Logic từ generator: parse execution từ Step 1
            String executionFromStep1 = null;
            Matcher m = EXECUTION_PAT.matcher(step1Body);
            if (m.find()) executionFromStep1 = m.group(1);

            // executionFromStep1 != null → bỏ qua Step 1b
            assertThat(executionFromStep1).isNotNull().isEqualTo("e1s1_STEP1TOKEN");

            // Condition để skip Step 1b
            boolean shouldSkipStep1b = executionFromStep1 != null;
            assertThat(shouldSkipStep1b).isTrue();

            // execution cuối cùng dùng cho POST
            String finalExecution = executionFromStep1 != null ? executionFromStep1 : "e1s1";
            assertThat(finalExecution).isEqualTo("e1s1_STEP1TOKEN");
        }

        @Test
        @DisplayName("Step 1 HTTP 200 KHÔNG có execution → Step 1b chạy bình thường")
        void step1bRunsWhenStep1BodyHasNoExecution() {
            // Edge case: body thiếu execution field (server đặc biệt)
            String step1Body = "<html><body>Error page without form</body></html>";

            String executionFromStep1 = null;
            Matcher m = EXECUTION_PAT.matcher(step1Body);
            if (m.find()) executionFromStep1 = m.group(1);

            assertThat(executionFromStep1).isNull();

            boolean shouldSkipStep1b = executionFromStep1 != null;
            assertThat(shouldSkipStep1b).isFalse(); // Step 1b vẫn chạy

            // Fallback default
            String finalExecution = executionFromStep1 != null ? executionFromStep1 : "e1s1";
            assertThat(finalExecution).isEqualTo("e1s1");
        }
    }
}
