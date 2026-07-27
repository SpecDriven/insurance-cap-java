package com.acme.insurance.ports;

import java.util.List;

/** An outbound email; attachments are document names (e.g. "id-cards.pdf"). */
public record EmailMessage(String to, String subject, String body, List<String> attachments) {

    public EmailMessage(String to, String subject, String body) {
        this(to, subject, body, List.of());
    }
}
