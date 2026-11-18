package ru.projectx.datareference.service.onec;

import java.util.Objects;
import org.springframework.stereotype.Component;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;
import ru.projectx.datareference.exception.OneCFieldException;
import ru.projectx.datareference.mapper.ImportResult;
import ru.projectx.datareference.mapper.stream.InsuredAssociationMapper;
import tools.jackson.databind.ObjectMapper;

@Component
public class InsuredAssociationReplacement implements TransferReplacement {

    private final InsuredAssociationMapper mapper;
    private final InsuredAssociationOrder order;
    private final TransferRows rows;

    public InsuredAssociationReplacement(InsuredAssociationMapper mapper, InsuredAssociationOrder order,
            ObjectMapper json) {

        this.mapper = mapper;
        this.order = order;
        this.rows = new TransferRows(json);
    }

    @Override
    public ImportResult replace(CorrelatedTransfer.Assembled transfer, ParsedTransferParts parsed) {
        InsuredAssociationFilter filter = commonFilterOf(transfer, parsed);
        return order.applyInOrder(filter, transfer.finalMessage(),
                () -> mapper.apply(filter, rows.inOrder(transfer, parsed)));
    }

    private InsuredAssociationFilter commonFilterOf(CorrelatedTransfer.Assembled transfer, ParsedTransferParts parsed) {
        InsuredAssociationFilter filter = mapper.filterOf(rows.dataOf(transfer.finalMessage(), parsed));
        boolean mixed = transfer.batches().stream()
                .map(batch -> mapper.filterOf(rows.dataOf(batch, parsed)))
                .anyMatch(batchFilter -> !filter.equals(batchFilter));
        if (mixed) {
            throw new OneCFieldException("части передачи %s называют разные отборы"
                    .formatted(Objects.requireNonNull(transfer.finalMessage().transferPart().correlationId())));
        }
        return filter;
    }
}
