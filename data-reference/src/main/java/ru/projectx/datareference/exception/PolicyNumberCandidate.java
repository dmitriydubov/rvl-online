package ru.projectx.datareference.exception;

import java.util.UUID;

public record PolicyNumberCandidate(UUID policyId, UUID contractExternalId) {
}
