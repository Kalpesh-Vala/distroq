package com.distroq.api;

import com.distroq.TestProperties;
import com.distroq.config.DistroqProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminReasonTest {

    private final AdminReason reason = new AdminReason(TestProperties.defaults());

    @Test
    void aMissingHeaderIsRejected() {
        assertThatThrownBy(() -> reason.requireHeader(null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("X-Admin-Reason")
                .extracting(failure -> ((ResponseStatusException) failure).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aBlankHeaderIsRejected() {
        assertThatThrownBy(() -> reason.requireHeader("   "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void aBlankBodyReasonIsRejectedAndNamesTheBodyField() {
        assertThatThrownBy(() -> reason.requireBody(""))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void aReasonLongerThanTheConfiguredMaximumIsRejected() {
        String tooLong = "x".repeat(TestProperties.ADMIN.maxReasonLength() + 1);

        assertThatThrownBy(() -> reason.requireHeader(tooLong))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at most 500 characters");
    }

    @Test
    void aReasonExactlyAtTheMaximumIsAccepted() {
        String exact = "x".repeat(TestProperties.ADMIN.maxReasonLength());

        assertThat(reason.requireHeader(exact)).isEqualTo(exact);
    }

    @Test
    void theStoredReasonIsTrimmed() {
        assertThat(reason.requireHeader("  Redis was restored  "))
                .isEqualTo("Redis was restored");
    }

    @Test
    void theMaximumComesFromConfigurationRatherThanAConstant() {
        AdminReason shorter = new AdminReason(TestProperties.of(new DistroqProperties.Admin(10)));

        assertThat(shorter.maxLength()).isEqualTo(10);
        assertThatThrownBy(() -> shorter.requireHeader("x".repeat(11)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at most 10 characters");
    }
}
