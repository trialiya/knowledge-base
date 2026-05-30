package io.github.trialiya.kb.model.chat.spring;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import org.springframework.ai.chat.messages.Message;

public interface IMessage extends Message {

    ChatMessageEntity chatMessage();
}
