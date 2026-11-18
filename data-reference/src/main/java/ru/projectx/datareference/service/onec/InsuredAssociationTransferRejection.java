package ru.projectx.datareference.service.onec;

import java.util.List;
import org.springframework.stereotype.Component;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.mapper.stream.InsuredAssociationMapper;
import ru.projectx.datareference.repository.inbound.InboundMessageRepository;
import ru.projectx.datareference.service.inbound.RejectionScope;
import tools.jackson.databind.ObjectMapper;

@Component
public class InsuredAssociationTransferRejection implements RejectionScope {

    private final TransferRejection rejection;

    public InsuredAssociationTransferRejection(InboundMessageRepository repository, InsuredAssociationMapper mapper,
            ObjectMapper json) {

        this.rejection = new TransferRejection(repository, json,
                new InsuredAssociationOwnChecks(mapper, new TransferPartCheck(mapper::checkRow)));
    }

    @Override
    public OneCStream stream() {
        return OneCStream.INSURED_ASSOCIATIONS;
    }

    @Override
    public List<InboundMessage> reject(InboundMessage message, String reason) {
        return rejection.reject(message, reason);
    }
}
