package ru.projectx.datareference.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import ru.projectx.datareference.dto.FamilyMember;
import ru.projectx.datareference.dto.FamilyResponse;
import ru.projectx.datareference.service.policy.FamilyReads;

@ExtendWith(MockitoExtension.class)
class FamilyControllerTest {

    private static final String FAMILY = InternalApi.BASE_PATH + "/policies/{id}/family";
    private static final UUID INSURED_ID = UUID.fromString("9011c000-0000-4000-8000-000000000001");
    private static final FamilyMember SPOUSE = new FamilyMember(
            UUID.fromString("9011c000-0000-4000-8000-000000000002"),
            UUID.fromString("9011c000-0000-4000-8000-000000000003"), "СТЕНД-0002", "Тестова", "Проба", null,
            LocalDate.of(2015, 1, 1), "Ж", null);

    @Mock
    private FamilyReads family;

    private MockMvcTester mvc;

    @BeforeEach
    void setUp() {
        mvc = StandaloneMvc.of(new FamilyController(family));
    }

    @Test
    void family_returnsRelativesOfTheInsuredPolicy_asJson() {
        when(family.family(INSURED_ID)).thenReturn(new FamilyResponse(List.of(SPOUSE)));

        MvcTestResult result = mvc.get().uri(FAMILY, INSURED_ID).exchange();

        assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().extractingPath("$.items[0].number").isEqualTo("СТЕНД-0002");
    }

    @Test
    void familyMember_returnsTheRelativeWithThatNumber_withoutWrapper() {
        when(family.member(INSURED_ID, "СТЕНД-0002")).thenReturn(SPOUSE);

        MvcTestResult result = mvc.get().uri(FAMILY + "?number={number}", INSURED_ID, "СТЕНД-0002").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.number").isEqualTo("СТЕНД-0002");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void familyMember_blankNumber_isMissingNumber_withoutAskingService(String number) {
        MvcTestResult result = mvc.get().uri(FAMILY, INSURED_ID).param("number", number).exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("VALIDATION_ERROR");
        assertThat(result).bodyJson().extractingPath("$.error.details[0].field").isEqualTo("number");
        assertThat(result).bodyJson().extractingPath("$.error.details[0].reason").isEqualTo("обязательный параметр");
        verifyNoInteractions(family);
    }

    @Test
    void family_unreadableId_isValidationErrorNamingId_withoutAskingService() {
        MvcTestResult result = mvc.get().uri(FAMILY, "not-a-uuid").exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error.details[0].field").isEqualTo("id");
        verifyNoInteractions(family);
    }
}
