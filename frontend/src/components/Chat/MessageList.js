// MessageList.js
import React, { useEffect, useRef } from 'react';
import Message from './Message';

const MessageList = ({ messages, onNavigateToDoc }) => {
  const containerRef = useRef(null);

  useEffect(() => {
    const el = containerRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messages]);

  return (
    <div className="message-list" ref={containerRef}>
      {messages.map((msg, index) => (
        <Message
          key={index}
          text={msg.text}
          sender={msg.sender}
          toolCalls={msg.toolCalls}
          onNavigateToDoc={onNavigateToDoc}
        />
      ))}
    </div>
  );
};

export default MessageList;
