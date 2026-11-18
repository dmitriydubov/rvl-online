package ru.projectx.datareference.entity.inbound;

public enum ParentKind {

    CONTRAGENT("контрагент"),
    LPU_CONTRACT("договор ЛПУ"),
    CONTRAGENT_SERVICE("услуга"),
    POLICY("полис");

    private final String title;

    ParentKind(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
