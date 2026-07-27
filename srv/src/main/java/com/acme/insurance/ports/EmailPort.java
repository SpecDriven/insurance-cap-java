package com.acme.insurance.ports;

/**
 * Outbound email port (see ARCHITECTURE.md "Outbound integrations").
 * Only an in-memory recording adapter is wired; no real provider.
 */
public interface EmailPort {

    void send(EmailMessage message);
}
