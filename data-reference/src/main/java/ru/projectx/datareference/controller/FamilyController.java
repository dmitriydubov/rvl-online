package ru.projectx.datareference.controller;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import ru.projectx.datareference.controller.error.RequestErrorAdvice;
import ru.projectx.datareference.dto.FamilyAnswer;
import ru.projectx.datareference.exception.InvalidRequestException;
import ru.projectx.datareference.service.policy.FamilyReads;

@InternalApi
public class FamilyController {

    private static final String NUMBER = "number";

    private final FamilyReads family;

    public FamilyController(FamilyReads family) {
        this.family = family;
    }

    @GetMapping("/policies/{id}/family")
    public FamilyAnswer family(@PathVariable UUID id, @RequestParam(required = false) @Nullable String number) {
        if (number == null) {
            return family.family(id);
        }
        if (number.isBlank()) {
            throw new InvalidRequestException(NUMBER, RequestErrorAdvice.REQUIRED_PARAMETER);
        }
        return family.member(id, number);
    }
}
