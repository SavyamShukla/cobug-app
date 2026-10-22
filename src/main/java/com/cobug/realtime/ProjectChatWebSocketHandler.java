package com.cobug.realtime;

import com.cobug.auth.UserRepository;
import com.cobug.chat.ProjectChatService;
import com.cobug.chat.ProjectChatMessage;
import com.cobug.projects.ProjectAccessService;
import com.cobug.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class ProjectChatWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ProjectChatWebSocketHandler.class);
    private final ObjectMapper mapper; private final JwtService jwt; private final UserRepository users; private final ProjectAccessService access; private final ProjectChatService chat;
    private final Map<UUID,Set<WebSocketSession>> rooms=new ConcurrentHashMap<>();
    public ProjectChatWebSocketHandler(ObjectMapper mapper,JwtService jwt,UserRepository users,ProjectAccessService access,ProjectChatService chat){this.mapper=mapper;this.jwt=jwt;this.users=users;this.access=access;this.chat=chat;}

    @Override public void afterConnectionEstablished(WebSocketSession session){session.getAttributes().put("authenticated",false);}
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message){
        try{
            JsonNode n=mapper.readTree(message.getPayload()); String type=n.path("type").asText("");
            if("AUTH".equalsIgnoreCase(type)){authenticate(session,n.path("token").asText(""));return;}
            if(!Boolean.TRUE.equals(session.getAttributes().get("authenticated"))){send(session,"ERROR",Map.of("message","Authenticate the WebSocket connection first."));return;}
            if("SUBSCRIBE".equalsIgnoreCase(type)){subscribe(session,n.path("projectId").asText(""));return;}
            if("SEND_MESSAGE".equalsIgnoreCase(type)){
                String clientIntent = n.hasNonNull("detectedIntent") ? n.path("detectedIntent").asText(null) : null;
                Double clientConfidence = n.hasNonNull("confidence") ? n.path("confidence").asDouble() : null;
                sendChat(session,n.path("projectId").asText(""),n.path("content").asText(""),n.path("clientMessageId").asText(""),clientIntent,clientConfidence);
                return;
            }
            if("TYPING_START".equalsIgnoreCase(type)){typing(session,n.path("projectId").asText(""),true);return;}
            if("TYPING_STOP".equalsIgnoreCase(type)){typing(session,n.path("projectId").asText(""),false);return;}
            if("DELETE_MESSAGE".equalsIgnoreCase(type)){deleteChat(session,n.path("projectId").asText(""),n.path("messageId").asText(""));return;}
            if("PING".equalsIgnoreCase(type)){send(session,"PONG",Map.of());return;}
        }catch(Exception e){try{send(session,"ERROR",Map.of("message",e.getMessage()==null?"WebSocket error":e.getMessage()));}catch(Exception ignored){}}
    }
    private void authenticate(WebSocketSession s,String token){if(token==null||token.isBlank()){send(s,"ERROR",Map.of("message","Missing authentication token."));return;}try{String email=jwt.extractEmail(token);var user=users.findByEmail(email).orElseThrow();s.getAttributes().put("authenticated",true);s.getAttributes().put("email",user.getEmail());s.getAttributes().put("userId",user.getId().toString());s.getAttributes().put("name",user.getName());send(s,"AUTH_OK",Map.of("email",user.getEmail(),"name",user.getName()));}catch(Exception e){send(s,"ERROR",Map.of("message","Invalid or expired authentication token."));}}
    private void subscribe(WebSocketSession s,String raw){UUID id=UUID.fromString(raw);String email=(String)s.getAttributes().get("email");access.requireMember(id,email);unsubscribe(s);rooms.computeIfAbsent(id,k->new CopyOnWriteArraySet<>()).add(s);s.getAttributes().put("projectId",id);send(s,"SUBSCRIBED",Map.of("projectId",id));}
    private void sendChat(WebSocketSession s,String raw,String content,String clientMessageId,String clientIntent,Double clientConfidence){
        UUID id=UUID.fromString(raw);
        String email=(String)s.getAttributes().get("email");
        access.requireMember(id,email);
        UUID subscribed=(UUID)s.getAttributes().get("projectId");
        if(!id.equals(subscribed)) throw new IllegalStateException("Subscribe to this project chat first.");

        ProjectChatMessage saved = chat.saveUserMessage(id,email,content);
        broadcast(id,"CHAT_MESSAGE",toMap(saved,clientMessageId));

        if (ProjectChatService.containsAiMention(content)) {
            broadcastTyping(id, "AI", "COBUG AI", "ai@cobug.local", true);
            CompletableFuture
                    .supplyAsync(() -> chat.generateAiReply(id, email, content, clientIntent, clientConfidence))
                    .thenAccept(ai -> {
                        if (ai != null) {
                            Map<String, Object> payload = toMap(ai);
                            // Send a dedicated AI event as well as the normal chat event.
                            // The requesting socket gets the direct event even if a room
                            // broadcast races with a reconnect. Other members still receive
                            // the normal CHAT_MESSAGE event.
                            send(s, "AI_RESPONSE", payload);
                            broadcast(id, "CHAT_MESSAGE", payload);
                        }
                        broadcastTyping(id, "AI", "COBUG AI", "ai@cobug.local", false);
                    })
                    .exceptionally(ex -> {
                        log.error("[CHAT AI] Async generation/delivery failed for project {}", id, ex);
                        broadcastTyping(id, "AI", "COBUG AI", "ai@cobug.local", false);
                        send(s, "AI_ERROR", Map.of("message", "COBUG AI could not complete the request right now."));
                        return null;
                    });
        }
    }

    private Map<String,Object> toMap(ProjectChatMessage m){
        return toMap(m,"");
    }
    private Map<String,Object> toMap(ProjectChatMessage m,String clientMessageId){
        Map<String,Object> map=new LinkedHashMap<>();
        map.put("id",m.getId());
        map.put("senderId",m.getSenderId());
        map.put("senderName",m.getSenderName());
        map.put("senderEmail",m.getSenderEmail());
        map.put("content",m.getContent());
        map.put("createdAt",m.getCreatedAt());
        map.put("aiGenerated",m.isAiGenerated());
        if(clientMessageId!=null && !clientMessageId.isBlank()) map.put("clientMessageId",clientMessageId);
        return map;
    }
    private void typing(WebSocketSession s,String raw,boolean isTyping){
        UUID id=UUID.fromString(raw);
        String email=(String)s.getAttributes().get("email");
        access.requireMember(id,email);
        UUID subscribed=(UUID)s.getAttributes().get("projectId");
        if(!id.equals(subscribed)) throw new IllegalStateException("Subscribe to this project chat first.");
        String userId=(String)s.getAttributes().get("userId");
        String name=(String)s.getAttributes().get("name");
        if(name==null || name.isBlank()) name=email;
        broadcastTyping(id,userId,name,email,isTyping);
    }

    private void broadcastTyping(UUID projectId,String senderId,String senderName,String senderEmail,boolean isTyping){
        broadcast(projectId,"CHAT_TYPING",Map.of(
                "senderId",senderId,
                "senderName",senderName,
                "senderEmail",senderEmail,
                "aiGenerated","ai@cobug.local".equalsIgnoreCase(senderEmail),
                "isTyping",isTyping
        ));
    }

    private void deleteChat(WebSocketSession s,String raw,String messageId){UUID id=UUID.fromString(raw);String email=(String)s.getAttributes().get("email");chat.delete(id,UUID.fromString(messageId),email);broadcast(id,"CHAT_DELETED",Map.of("messageId",messageId));}
    private void broadcast(UUID projectId,String type,Object payload){Set<WebSocketSession> set=rooms.getOrDefault(projectId,Set.of());for(WebSocketSession s:set)if(s.isOpen())send(s,type,payload);}
    private void send(WebSocketSession s,String type,Object payload){
        try{
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("type",type);
            if(payload instanceof Map<?,?> map)m.putAll((Map)map);
            else if(payload!=null)m.put("message",payload);
            synchronized(s){
                if(s.isOpen()) s.sendMessage(new TextMessage(mapper.writeValueAsString(m)));
            }
        }catch(IOException | IllegalStateException ignored){}
    }
    private void unsubscribe(WebSocketSession s){
        Object old=s.getAttributes().remove("projectId");
        if(old instanceof UUID id){
            String senderId=(String)s.getAttributes().get("userId");
            String email=(String)s.getAttributes().get("email");
            if(senderId!=null && email!=null){
                String name=(String)s.getAttributes().get("name");
                if(name==null || name.isBlank()) name=email;
                broadcastTyping(id,senderId,name,email,false);
            }
            Set<WebSocketSession> set=rooms.get(id);
            if(set!=null){set.remove(s);if(set.isEmpty())rooms.remove(id);}
        }
    }
    @Override public void afterConnectionClosed(WebSocketSession s,CloseStatus status){unsubscribe(s);}
}
