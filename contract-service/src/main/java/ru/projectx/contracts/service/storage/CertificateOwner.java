package ru.projectx.contracts.service.storage;

import java.util.UUID;

record CertificateOwner(UUID individualRequestId, UUID participantId) {
}
