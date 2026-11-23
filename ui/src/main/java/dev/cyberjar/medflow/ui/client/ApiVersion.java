package dev.cyberjar.medflow.ui.client;

public enum ApiVersion {
    V1("1.0"),
    V2("2.0");

    private final String value;

    ApiVersion(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
