package ru.projectx.datareference.service.onec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.projectx.datareference.entity.inbound.AwaitedParent;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundReceipt;
import ru.projectx.datareference.entity.inbound.InsuredAssociationFinalization;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.entity.inbound.ParentKind;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;
import ru.projectx.datareference.mapper.ImportResult;
import ru.projectx.datareference.repository.inbound.InsuredAssociationFinalizationRepository;

@ExtendWith(MockitoExtension.class)
class InsuredAssociationOrderTest {

    private static final UUID INSURED = UUID.fromString("11111111-2222-4333-8444-555555555501");
    private static final UUID NEWER_CID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee09");
    private static final Instant RECEIVED_AT = Instant.parse("2026-11-15T09:00:00Z");
    private static final InsuredAssociationFilter FILTER = new InsuredAssociationFilter(null, INSURED, null, null);

    @Mock
    private InsuredAssociationFinalizationRepository finalizations;

    private final InboundMessage finalMessage = InboundMessage.receive(new InboundReceipt(
            UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee12"), OneCStream.INSURED_ASSOCIATIONS, "очередь",
            RECEIVED_AT), "{}");

    @Test
    void newerOverlappingTransferAlreadyApplied_skipsThisOne_withoutReplacing() {
        when(finalizations.findNewerOverlapping(FILTER, RECEIVED_AT)).thenReturn(Optional.of(NEWER_CID));
        AtomicInteger replacements = new AtomicInteger();

        ImportResult result = order().applyInOrder(FILTER, finalMessage, counting(replacements, ImportResult.applied()));

        assertThat(result).isEqualTo(ImportResult.skipped(
                "по отбору передачи уже применена более новая передача " + NEWER_CID));
        assertThat(replacements).hasValue(0);
        verify(finalizations, never()).save(any());
    }

    @Test
    void appliedReplacement_isRememberedWithItsFinalMessageAndFilter() {
        when(finalizations.findNewerOverlapping(FILTER, RECEIVED_AT)).thenReturn(Optional.empty());

        ImportResult result = order().applyInOrder(FILTER, finalMessage, ImportResult::applied);

        assertThat(result).isEqualTo(ImportResult.applied());
        ArgumentCaptor<InsuredAssociationFinalization> remembered = ArgumentCaptor.captor();
        verify(finalizations).save(remembered.capture());
        assertThat(remembered.getValue()).isNotNull().usingRecursiveComparison().isEqualTo(
                InsuredAssociationFinalization.of(finalMessage, FILTER));
    }

    @Test
    void replacementWaitingForAPolicy_isNotRemembered() {
        when(finalizations.findNewerOverlapping(FILTER, RECEIVED_AT)).thenReturn(Optional.empty());
        ImportResult awaiting = ImportResult.awaitingParent(new AwaitedParent(ParentKind.POLICY, INSURED));

        ImportResult result = order().applyInOrder(FILTER, finalMessage, () -> awaiting);

        assertThat(result).isEqualTo(awaiting);
        verify(finalizations, never()).save(any());
    }

    private InsuredAssociationOrder order() {
        return new InsuredAssociationOrder(finalizations);
    }

    private static Supplier<ImportResult> counting(AtomicInteger calls, ImportResult result) {
        return () -> {
            calls.incrementAndGet();
            return result;
        };
    }
}
