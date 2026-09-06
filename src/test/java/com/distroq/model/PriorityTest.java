package com.distroq.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityTest {

    @Test
    void normalIsTheDefault() {
        assertThat(Priority.DEFAULT).isEqualTo(Priority.NORMAL);
    }

    @Test
    void declarationOrderIsTheStrictSchedulingOrder() {
        assertThat(Priority.STRICT_ORDER)
                .containsExactly(Priority.HIGH, Priority.NORMAL, Priority.LOW);
        assertThat(Priority.LOWEST).isEqualTo(Priority.LOW);
    }

    @Test
    void orDefaultResolvesNullWithoutThrowing() {
        assertThat(Priority.orDefault(null)).isEqualTo(Priority.NORMAL);
        assertThat(Priority.orDefault(Priority.HIGH)).isEqualTo(Priority.HIGH);
        assertThat(Priority.orDefault(Priority.LOW)).isEqualTo(Priority.LOW);
    }

    @Test
    void absentPriorityResolvesToTheDefault() {
        assertThat(Priority.parse(null)).contains(Priority.NORMAL);
        assertThat(Priority.parse("")).contains(Priority.NORMAL);
        assertThat(Priority.parse("   ")).contains(Priority.NORMAL);
    }

    @Test
    void parsingIsCaseInsensitive() {
        for (String raw : List.of("HIGH", "high", "High", "hIgH")) {
            assertThat(Priority.parse(raw)).as(raw).contains(Priority.HIGH);
        }
        assertThat(Priority.parse("normal")).contains(Priority.NORMAL);
        assertThat(Priority.parse("LoW")).contains(Priority.LOW);
    }

    @Test
    void surroundingWhitespaceIsTolerated() {
        assertThat(Priority.parse("  high  ")).contains(Priority.HIGH);
    }

    @Test
    void unknownValueIsRejectedRatherThanSilentlyDefaulted() {
        // silently defaulting would mean a typo'd "hihg" runs at NORMAL and nobody ever finds out
        assertThat(Priority.parse("urgent")).isEmpty();
        assertThat(Priority.parse("hihg")).isEmpty();
        assertThat(Priority.parse("1")).isEmpty();
    }

    @Test
    void rejectionCanNameTheValidValues() {
        assertThat(Priority.validValues()).isEqualTo("HIGH, NORMAL, LOW");
    }

    @Test
    void keySuffixIsTheLowercasedName() {
        assertThat(Priority.HIGH.keySuffix()).isEqualTo("high");
        assertThat(Priority.NORMAL.keySuffix()).isEqualTo("normal");
        assertThat(Priority.LOW.keySuffix()).isEqualTo("low");
    }

    @Test
    void everyTierRoundTripsThroughItsOwnName() {
        for (Priority priority : Priority.values()) {
            Optional<Priority> parsed = Priority.parse(priority.name());
            assertThat(parsed).as(priority.name()).contains(priority);
        }
    }
}
