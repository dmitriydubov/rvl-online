package ru.projectx.datareference.service.onec;

import org.springframework.stereotype.Component;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.mapper.stream.PriceListMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class PriceListTransferAssembler implements StreamHandler {

    private final TransferAssembly assembly;
    private final TransferRules rules;

    public PriceListTransferAssembler(TransferAssembly assembly, PriceListMapper priceListMapper,
            PriceTransferServiceLock serviceLock, PriceListReplacement replacement) {

        this.assembly = assembly;
        this.rules = new TransferRules(serviceLock::lockServiceOf, new TransferPartCheck(priceListMapper::checkRow),
                replacement);
    }

    @Override
    public OneCStream stream() {
        return OneCStream.PRICE_LISTS;
    }

    @Override
    public ImportOutcome handle(InboundMessage message, ObjectNode data) {
        return assembly.assemble(message, data, rules);
    }
}
