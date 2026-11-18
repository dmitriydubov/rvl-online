package ru.projectx.datareference.service.onec;

import org.springframework.stereotype.Component;
import ru.projectx.datareference.mapper.ImportResult;
import ru.projectx.datareference.mapper.stream.PriceListMapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class PriceListReplacement implements TransferReplacement {

    private final PriceListMapper priceListMapper;
    private final PriceTransferOrder order;
    private final PriceListTransferRows rows;

    public PriceListReplacement(PriceListMapper priceListMapper, PriceTransferOrder order, ObjectMapper json) {
        this.priceListMapper = priceListMapper;
        this.order = order;
        this.rows = new PriceListTransferRows(json);
    }

    @Override
    public ImportResult replace(CorrelatedTransfer.Assembled transfer, ParsedTransferParts parsed) {
        ObjectNode assembled = rows.of(transfer, parsed);
        return order.applyInOrder(PriceListTransferRows.serviceOf(assembled), transfer.finalMessage(),
                () -> priceListMapper.apply(assembled));
    }
}
