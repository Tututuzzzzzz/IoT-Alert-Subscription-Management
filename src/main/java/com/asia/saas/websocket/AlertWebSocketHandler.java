package com.asia.saas.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Slf4j
public class AlertWebSocketHandler extends TextWebSocketHandler {

    // Map to keep track of active sessions for each user: userId -> list of sessions
    private final Map<Long, List<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        try {
            String query = session.getUri() != null ? session.getUri().getQuery() : null;
            if (query != null && query.contains("userId=")) {
                String userIdStr = query.split("userId=")[1].split("&")[0];
                Long userId = Long.parseLong(userIdStr);
                
                session.getAttributes().put("userId", userId);
                userSessions.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(session);
                
                log.info("WebSocket connection established for user {}. Session ID: {}", userId, session.getId());
            } else {
                log.warn("WebSocket connection attempt missing userId parameter: {}", session.getUri());
                session.close(CloseStatus.BAD_DATA);
            }
        } catch (Exception e) {
            log.error("Error setting up WebSocket session: {}", e.getMessage(), e);
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignored) {}
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            List<WebSocketSession> sessions = userSessions.get(userId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    userSessions.remove(userId);
                }
            }
            log.info("WebSocket connection closed for user {}. Session ID: {}", userId, session.getId());
        }
    }

    public void sendAlertToUser(Long userId, String alertJson) {
        List<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            log.debug("No active WebSocket sessions found for user {}", userId);
            return;
        }

        TextMessage message = new TextMessage(alertJson);
        log.info("Broadcasting alert to {} active sessions for user {}", sessions.size(), userId);
        
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(message);
                } catch (IOException e) {
                    log.error("Failed to send WebSocket message to session {}: {}", session.getId(), e.getMessage());
                }
            }
        }
    }
}
