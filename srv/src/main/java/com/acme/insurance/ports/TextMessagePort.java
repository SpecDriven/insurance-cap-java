package com.acme.insurance.ports;

/**
 * Outbound text-messaging port (see ARCHITECTURE.md "Outbound integrations").
 */
public interface TextMessagePort {

    void send(TextMessage message);
}
