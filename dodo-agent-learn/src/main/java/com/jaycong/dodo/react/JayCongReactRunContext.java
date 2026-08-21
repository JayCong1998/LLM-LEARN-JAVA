package com.jaycong.dodo.react;


import lombok.Data;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author pyc
 * @since 2026-08-21 14:47
 */
@Data
public class JayCongReactRunContext {

    private String conversationId;
    //消息记录
    private List<Message> messages = Collections.synchronizedList(new ArrayList<>());
    // 迭代轮次
    private AtomicLong roundCounter = new AtomicLong(0);
    // 是否发送最终结果标记位
    private AtomicBoolean finished = new AtomicBoolean(false);
    //当前用户的提问
    private String currentUserMessage;

    public JayCongReactRunContext(String conversationId) {
        this.conversationId = conversationId;
    }

    public synchronized void addMessage(Message message) { // 串行追加一条系统、用户、助手或工具响应消息。
        messages.add(message); // 将消息放到历史尾部以保持模型对话时序。
    }
}
