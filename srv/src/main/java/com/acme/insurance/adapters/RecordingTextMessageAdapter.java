package com.acme.insurance.adapters;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import com.acme.insurance.ports.TextMessage;
import com.acme.insurance.ports.TextMessagePort;

/** In-memory stub adapter recording outbound text messages. */
@Component
public class RecordingTextMessageAdapter implements TextMessagePort {

    private final List<TextMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(TextMessage message) {
        sent.add(message);
    }

    public List<TextMessage> sent() {
        return List.copyOf(sent);
    }

    public void reset() {
        sent.clear();
    }
}
