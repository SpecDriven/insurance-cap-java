package com.acme.insurance.adapters;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import com.acme.insurance.ports.EmailMessage;
import com.acme.insurance.ports.EmailPort;

/**
 * In-memory stub adapter; tests assert on the recorded outbound calls
 * (ARCHITECTURE.md "Outbound integrations").
 */
@Component
public class RecordingEmailAdapter implements EmailPort {

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(EmailMessage message) {
        sent.add(message);
    }

    public List<EmailMessage> sent() {
        return List.copyOf(sent);
    }

    public void reset() {
        sent.clear();
    }
}
