package ru.projectx.datareference.dto;

import java.util.List;

public record FamilyResponse(List<FamilyMember> items) implements FamilyAnswer {
}
