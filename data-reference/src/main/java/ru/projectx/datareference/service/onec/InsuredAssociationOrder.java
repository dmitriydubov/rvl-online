package ru.projectx.datareference.service.onec;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InsuredAssociationFinalization;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;
import ru.projectx.datareference.mapper.ImportResult;
import ru.projectx.datareference.repository.inbound.InsuredAssociationFinalizationRepository;

@Component
public class InsuredAssociationOrder {

    private final InsuredAssociationFinalizationRepository finalizations;

    public InsuredAssociationOrder(InsuredAssociationFinalizationRepository finalizations) {
        this.finalizations = finalizations;
    }

    ImportResult applyInOrder(
            InsuredAssociationFilter filter, InboundMessage finalMessage, Supplier<ImportResult> replacement) {

        return finalizations.findNewerOverlapping(filter, finalMessage.receipt().receivedAt())
                .map(newer -> ImportResult.skipped("по отбору передачи уже применена более новая передача " + newer))
                .orElseGet(() -> replaceAndRemember(filter, finalMessage, replacement));
    }

    private ImportResult replaceAndRemember(
            InsuredAssociationFilter filter, InboundMessage finalMessage, Supplier<ImportResult> replacement) {

        ImportResult result = replacement.get();
        if (result instanceof ImportResult.Applied) {
            finalizations.save(InsuredAssociationFinalization.of(finalMessage, filter));
        }
        return result;
    }
}
