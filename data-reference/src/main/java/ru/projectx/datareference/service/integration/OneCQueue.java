package ru.projectx.datareference.service.integration;

import java.util.function.Function;
import ru.projectx.datareference.config.OneCQueueProperties;
import ru.projectx.datareference.entity.inbound.OneCStream;

enum OneCQueue {

    POLICIES(OneCQueueProperties::policies),
    INSURANCE_CONTRACTS(OneCQueueProperties::insuranceContracts),
    CONTRAGENTS(OneCQueueProperties::contragents),
    MKB10(OneCQueueProperties::mkb10),
    LPU_CONTRACTS(OneCQueueProperties::lpuContracts),
    PROGRAMS(OneCQueueProperties::programs),
    APPEAL_TYPES(OneCQueueProperties::appealTypes),
    LPU_CONTACTS(OneCQueueProperties::lpuContacts),
    PRICE_LISTS(OneCQueueProperties::priceLists),
    PRICE_LISTS_FINAL(OneCStream.PRICE_LISTS, OneCQueueProperties::priceListsFinal),
    CONTRAGENT_SERVICES(OneCQueueProperties::contragentServices),
    INSURED_ASSOCIATIONS(OneCQueueProperties::insuredAssociations),
    INSURED_ASSOCIATIONS_FINAL(OneCStream.INSURED_ASSOCIATIONS, OneCQueueProperties::insuredAssociationsFinal);

    private final OneCStream stream;
    private final Function<OneCQueueProperties, String> nameProperty;

    OneCQueue(Function<OneCQueueProperties, String> nameProperty) {
        this.stream = OneCStream.valueOf(name());
        this.nameProperty = nameProperty;
    }

    OneCQueue(OneCStream stream, Function<OneCQueueProperties, String> nameProperty) {
        this.stream = stream;
        this.nameProperty = nameProperty;
    }

    OneCStream stream() {
        return stream;
    }

    String nameIn(OneCQueueProperties queues) {
        return nameProperty.apply(queues);
    }
}
