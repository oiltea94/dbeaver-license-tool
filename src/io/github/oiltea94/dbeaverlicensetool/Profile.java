package io.github.oiltea94.dbeaverlicensetool;
import java.time.LocalDate;
public record Profile(String name, String email, String company, LocalDate expires, int users) {}
