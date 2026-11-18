package ru.projectx.datareference.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("projectx.onec.queues")
public record OneCQueueProperties(
        @NotBlank String policies,
        @NotBlank String insuranceContracts,
        @NotBlank String contragents,
        @NotBlank String mkb10,
        @NotBlank String lpuContracts,
        @NotBlank String programs,
        @NotBlank String appealTypes,
        @NotBlank String lpuContacts,
        @NotBlank String priceLists,
        @NotBlank String priceListsFinal,
        @NotBlank String contragentServices,
        @NotBlank String insuredAssociations,
        @NotBlank String insuredAssociationsFinal) {
}
