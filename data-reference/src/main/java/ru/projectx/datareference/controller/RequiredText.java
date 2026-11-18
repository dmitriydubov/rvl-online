package ru.projectx.datareference.controller;

import ru.projectx.datareference.controller.error.RequestErrorAdvice;
import ru.projectx.datareference.exception.InvalidRequestException;

final class RequiredText {

    private RequiredText() {
    }

    static String of(String parameter, String value) {
        if (value.isBlank()) {
            throw new InvalidRequestException(parameter, RequestErrorAdvice.REQUIRED_PARAMETER);
        }
        return value;
    }
}
