package ru.projectx.datareference.service.schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.repository.database.DatabaseClockRepository;
import ru.projectx.datareference.repository.inbound.BufferedTransfer;
import ru.projectx.datareference.repository.inbound.InboundMessageRepository;
import ru.projectx.datareference.repository.inbound.QuarantineRepository;

@ExtendWith(MockitoExtension.class)
class AgedInboundMessagesTest {

    private static final Instant NOW = Instant.parse("2026-10-20T08:30:00.123456Z");

    @Mock
    private InboundMessageRepository repository;

    @Mock
    private QuarantineRepository quarantine;

    @Mock
    private DatabaseClockRepository databaseClock;

    @BeforeEach
    void databaseClockShowsNow() {
        when(databaseClock.currentMoment()).thenReturn(NOW);
    }

    @Test
    void forgetPayloads_erasesRowsReceivedBeforeDatabaseNowMinusAge() {
        when(repository.forgetPayloadsReceivedBefore(NOW.minus(Duration.ofDays(30)), 500)).thenReturn(17);

        assertThat(agedMessages().forgetPayloads(Duration.ofDays(30), 500)).isEqualTo(17);
    }

    @Test
    void forgetQuarantineBodies_erasesRowsReceivedBeforeDatabaseNowMinusAge() {
        when(quarantine.forgetBodies(NOW.minus(Duration.ofDays(7)), 50)).thenReturn(3);

        assertThat(agedMessages().forgetQuarantineBodies(Duration.ofDays(7), 50)).isEqualTo(3);
    }

    @Test
    void transfersBufferedLongerThan_asksForTransfersStartedBeforeDatabaseNowMinusAge() {
        List<BufferedTransfer> stuck = List.of(mock(BufferedTransfer.class));
        when(repository.findTransfersBufferedBefore(OneCStream.INSURED_ASSOCIATIONS, NOW.minus(Duration.ofHours(1)),
                Limit.of(20))).thenReturn(stuck);

        assertThat(agedMessages().transfersBufferedLongerThan(OneCStream.INSURED_ASSOCIATIONS, Duration.ofHours(1), 20)).isSameAs(stuck);
    }

    @Test
    void allTransfersBufferedLongerThan_asksWithoutLimit() {
        List<BufferedTransfer> stuck = List.of(mock(BufferedTransfer.class));
        when(repository.findTransfersBufferedBefore(OneCStream.PRICE_LISTS, NOW.minus(Duration.ofHours(1)), Limit.unlimited()))
                .thenReturn(stuck);

        assertThat(agedMessages().allTransfersBufferedLongerThan(OneCStream.PRICE_LISTS, Duration.ofHours(1))).isSameAs(stuck);
    }

    private AgedInboundMessages agedMessages() {
        return new AgedInboundMessages(repository, quarantine, databaseClock);
    }
}
