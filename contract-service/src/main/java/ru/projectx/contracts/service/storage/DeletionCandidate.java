package ru.projectx.contracts.service.storage;

import org.jspecify.annotations.Nullable;

record DeletionCandidate(String fileKey, @Nullable CertificateOwner certificateOwner) {
}
