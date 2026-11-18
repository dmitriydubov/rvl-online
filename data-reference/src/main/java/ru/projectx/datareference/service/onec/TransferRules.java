package ru.projectx.datareference.service.onec;

import java.util.UUID;
import java.util.function.BiConsumer;
import tools.jackson.databind.node.ObjectNode;

record TransferRules(BiConsumer<UUID, ObjectNode> preparation, TransferPartCheck partCheck,
        TransferReplacement replacement) {
}
