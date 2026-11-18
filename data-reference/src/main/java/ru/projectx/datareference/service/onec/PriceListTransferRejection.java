package ru.projectx.datareference.service.onec;

import java.util.List;
import org.springframework.stereotype.Component;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.mapper.stream.PriceListMapper;
import ru.projectx.datareference.repository.inbound.InboundMessageRepository;
import ru.projectx.datareference.service.inbound.RejectionScope;
import tools.jackson.databind.ObjectMapper;

@Component
public class PriceListTransferRejection implements RejectionScope {

    private final TransferRejection rejection;

    public PriceListTransferRejection(InboundMessageRepository repository, PriceTransferServiceLock serviceLock,
            PriceListMapper priceList, ObjectMapper json) {

        this.rejection = new TransferRejection(repository, json, new PriceTransferOwnChecks(serviceLock,
                new PriceTransferServiceCheck(repository), new TransferPartCheck(priceList::checkRow)));
    }

    @Override
    public OneCStream stream() {
        return OneCStream.PRICE_LISTS;
    }

    @Override
    public List<InboundMessage> reject(InboundMessage message, String reason) {
        return rejection.reject(message, reason);
    }
}
