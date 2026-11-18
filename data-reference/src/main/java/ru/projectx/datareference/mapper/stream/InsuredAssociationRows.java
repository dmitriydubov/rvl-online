package ru.projectx.datareference.mapper.stream;

import static ru.projectx.datareference.mapper.value.OptionalFields.dateOrNull;

import java.util.List;
import ru.projectx.datareference.entity.policy.InsuredAssociation;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;
import ru.projectx.datareference.exception.OneCFieldException;
import ru.projectx.datareference.mapper.value.OneCNamedValues;
import ru.projectx.datareference.mapper.value.OneCValues;
import tools.jackson.databind.JsonNode;

final class InsuredAssociationRows {

    private InsuredAssociationRows() {
    }

    static InsuredAssociation.Row rowOf(JsonNode row) {
        return new InsuredAssociation.Row(
                OneCValues.requiredUuid(row, "idInsured"),
                OneCNamedValues.requiredUuid(row.path("otherSide"), "value", "otherSide.value"),
                OneCValues.requiredUuid(row, "relationType"),
                OneCValues.requiredDate(row, "period"),
                dateOrNull(row, "periodTo"));
    }

    static void requireConsistent(InsuredAssociationFilter filter, List<InsuredAssociation.Row> rows) {
        if (!rows.stream().allMatch(filter::covers)) {
            throw new OneCFieldException("строка связи вне отбора передачи");
        }
        if (rows.stream().map(InsuredAssociation.Row::dimensions).distinct().count() < rows.size()) {
            throw new OneCFieldException("в передаче две строки связи с одинаковыми измерениями");
        }
    }
}
