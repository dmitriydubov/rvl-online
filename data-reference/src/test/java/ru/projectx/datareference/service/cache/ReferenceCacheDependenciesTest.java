package ru.projectx.datareference.service.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.projectx.datareference.config.cache.ReferenceCaches.ACTIVE_INSURANCE_CONTRACTS_BY_INSURER;
import static ru.projectx.datareference.config.cache.ReferenceCaches.INSURANCE_CONTRACTS_BY_EXTERNAL_ID;
import static ru.projectx.datareference.config.cache.ReferenceCaches.INSURANCE_CONTRACTS_BY_ID;
import static ru.projectx.datareference.config.cache.ReferenceCaches.LPU_CARD;
import static ru.projectx.datareference.config.cache.ReferenceCaches.LPU_CATALOG;
import static ru.projectx.datareference.config.cache.ReferenceCaches.LPU_SERVICES;
import static ru.projectx.datareference.config.cache.ReferenceCaches.LPU_SERVICES_WITH_PRICES;
import static ru.projectx.datareference.config.cache.ReferenceCaches.MKB10_CODES;
import static ru.projectx.datareference.config.cache.ReferenceCaches.MKB10_SEARCH;
import static ru.projectx.datareference.config.cache.ReferenceCaches.PROGRAMS;
import static ru.projectx.datareference.config.cache.ReferenceCaches.SERVICE_PRICES;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.projectx.datareference.config.cache.ReferenceCaches;
import ru.projectx.datareference.entity.inbound.OneCStream;

class ReferenceCacheDependenciesTest {

    static Stream<Arguments> cachesByStream() {
        return Stream.of(
                Arguments.of(OneCStream.POLICIES, Set.of(LPU_CATALOG)),
                Arguments.of(OneCStream.INSURANCE_CONTRACTS,
                        Set.of(LPU_CATALOG, INSURANCE_CONTRACTS_BY_ID, INSURANCE_CONTRACTS_BY_EXTERNAL_ID,
                                ACTIVE_INSURANCE_CONTRACTS_BY_INSURER)),
                Arguments.of(OneCStream.CONTRAGENTS, Set.of(LPU_CATALOG, LPU_CARD, LPU_SERVICES,
                        LPU_SERVICES_WITH_PRICES, SERVICE_PRICES, INSURANCE_CONTRACTS_BY_ID,
                        INSURANCE_CONTRACTS_BY_EXTERNAL_ID, ACTIVE_INSURANCE_CONTRACTS_BY_INSURER, PROGRAMS)),
                Arguments.of(OneCStream.MKB10, Set.of(MKB10_SEARCH, MKB10_CODES)),
                Arguments.of(OneCStream.LPU_CONTRACTS,
                        Set.of(LPU_CATALOG, LPU_CARD, LPU_SERVICES, LPU_SERVICES_WITH_PRICES, SERVICE_PRICES)),
                Arguments.of(OneCStream.PROGRAMS, Set.of(LPU_CATALOG, PROGRAMS)),
                Arguments.of(OneCStream.APPEAL_TYPES,
                        Set.of(LPU_CATALOG, LPU_SERVICES, LPU_SERVICES_WITH_PRICES, SERVICE_PRICES)),
                Arguments.of(OneCStream.LPU_CONTACTS, Set.of(LPU_CATALOG, LPU_CARD)),
                Arguments.of(OneCStream.PRICE_LISTS, Set.of(LPU_SERVICES_WITH_PRICES, SERVICE_PRICES)),
                Arguments.of(OneCStream.CONTRAGENT_SERVICES,
                        Set.of(LPU_SERVICES, LPU_SERVICES_WITH_PRICES, SERVICE_PRICES)),
                Arguments.of(OneCStream.INSURED_ASSOCIATIONS, Set.of()));
    }

    @ParameterizedTest
    @MethodSource("cachesByStream")
    void messageOfStream_evictsExactlyCachesBuiltFromItsTables(OneCStream stream, Set<String> caches) {
        assertThat(ReferenceCacheDependencies.cachesOf(stream)).containsExactlyInAnyOrderElementsOf(caches);
    }

    @Test
    void everyStreamIsListed() {
        assertThat(cachesByStream().map(arguments -> arguments.get()[0]))
                .containsExactlyInAnyOrder((Object[]) OneCStream.values());
    }

    @Test
    void everyCacheIsEvictedBySomeStream() {
        Set<String> evicted = Arrays.stream(OneCStream.values())
                .flatMap(stream -> ReferenceCacheDependencies.cachesOf(stream).stream())
                .collect(Collectors.toSet());

        assertThat(evicted).containsExactlyInAnyOrderElementsOf(ReferenceCaches.ALL);
    }
}
