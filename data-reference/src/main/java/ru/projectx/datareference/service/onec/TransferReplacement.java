package ru.projectx.datareference.service.onec;

import ru.projectx.datareference.mapper.ImportResult;

interface TransferReplacement {

    ImportResult replace(CorrelatedTransfer.Assembled transfer, ParsedTransferParts parsed);
}
