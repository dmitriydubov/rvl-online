package ru.projectx.datareference.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.ru.Дано;
import io.cucumber.java.ru.Тогда;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import ru.projectx.datareference.config.ImportProperties;
import tools.jackson.databind.json.JsonMapper;

public class OneCRetentionSteps {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Map<String, UnaryOperator<Duration>> AGE_AROUND_TTL = Map.of(
            "больше", ttl -> ttl.plus(HOUR),
            "меньше", ttl -> ttl.minus(HOUR));
    private static final Map<String, Boolean> ERASED = Map.of("стёрты", true, "сохранены", false);
    private static final Map<String, Boolean> BODY_ERASED = Map.of("стёрто", true, "сохранено", false);

    private static final JsonMapper JSON = JsonMapper.shared();

    private final OneCInbox inbox;
    private final ImportProperties properties;
    private @Nullable InboundRow beforeRetention;

    public OneCRetentionSteps(OneCInbox inbox, ImportProperties properties) {
        this.inbox = inbox;
        this.properties = properties;
    }

    @Дано("с момента приёма последнего сообщения прошло на час {word} срока хранения")
    public void lastMessageAgedAroundTtl(String moreOrLess) {
        assertThat(AGE_AROUND_TTL).as("давность, которую умеет изображать шаг").containsKey(moreOrLess);
        beforeRetention = inbox.last();
        inbox.ageFromLast(AGE_AROUND_TTL.get(moreOrLess).apply(properties.payloadTtl()));
    }

    @Тогда("тело и причина отказа последнего сообщения {word}")
    public void bodyAndReason(String state) {
        assertThat(ERASED).as("состояния тела и причины, которые знает шаг").containsKey(state);
        InboundRow before = rowBeforeRetention();
        InboundRow after = inbox.last();

        if (ERASED.get(state)) {
            assertThat(after.payload()).as("тело последнего сообщения").isNull();
            assertThat(after.lastError()).as("причина отказа последнего сообщения").isNull();
        }
        else {
            assertThat(after.payload()).as("тело последнего сообщения").isNotNull().isEqualTo(before.payload());
            assertThat(after.lastError()).as("причина отказа последнего сообщения").isNotNull()
                    .isEqualTo(before.lastError());
        }
    }

    @Тогда("тело последнего сообщения {word}")
    public void body(String state) {
        assertThat(BODY_ERASED).as("состояния тела, которые знает шаг").containsKey(state);
        InboundRow after = inbox.last();

        if (BODY_ERASED.get(state)) {
            assertThat(after.payload()).as("тело последнего сообщения").isNull();
        }
        else {
            assertThat(after.payload()).as("тело последнего сообщения").isNotNull()
                    .isEqualTo(rowBeforeRetention().payload());
        }
    }

    @Тогда("тело сообщения 1С:УСК {string} {word}")
    public void bodyOf(String messageId, String state) {
        assertThat(BODY_ERASED).as("состояния тела, которые знает шаг").containsKey(state);
        String payload = inbox.row(UUID.fromString(messageId)).payload();

        if (BODY_ERASED.get(state)) {
            assertThat(payload).as("тело сообщения %s", messageId).isNull();
        }
        else {
            assertThat(payload).as("тело сообщения %s", messageId).isNotNull();
            assertThat(JSON.readTree(payload)).as("тело сообщения %s — присланный конверт целиком", messageId)
                    .isEqualTo(JSON.readTree(inbox.envelopeOf(UUID.fromString(messageId))));
        }
    }

    @Тогда("статус и число попыток последнего сообщения те же, что до прохода стирания")
    public void statusAndAttemptsKept() {
        InboundRow before = rowBeforeRetention();
        InboundRow after = inbox.last();

        assertThat(after.status()).as("статус последнего сообщения").isEqualTo(before.status());
        assertThat(after.attempt()).as("число попыток последнего сообщения").isEqualTo(before.attempt());
    }

    private InboundRow rowBeforeRetention() {
        assertThat(beforeRetention).as("строку до стирания запомнил шаг «прошло … срока хранения»").isNotNull();
        return beforeRetention;
    }
}
