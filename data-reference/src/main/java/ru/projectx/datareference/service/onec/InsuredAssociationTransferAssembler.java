package ru.projectx.datareference.service.onec;

import org.springframework.stereotype.Component;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.mapper.stream.InsuredAssociationMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class InsuredAssociationTransferAssembler implements StreamHandler {

    private final TransferAssembly assembly;
    private final TransferRules rules;

    public InsuredAssociationTransferAssembler(TransferAssembly assembly, InsuredAssociationMapper mapper,
            InsuredAssociationReplacement replacement) {

        this.assembly = assembly;
        this.rules = new TransferRules((cid, part) -> mapper.filterOf(part), new TransferPartCheck(mapper::checkRow),
                replacement);
    }

    @Override
    public OneCStream stream() {
        return OneCStream.INSURED_ASSOCIATIONS;
    }

    @Override
    public ImportOutcome handle(InboundMessage message, ObjectNode data) {
        return assembly.assemble(message, data, rules);
    }
}
