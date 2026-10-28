package com.fishmatrix.chat.controller;

import com.fishmatrix.chat.auth.ChatAuthInterceptor;
import com.fishmatrix.chat.auth.WsTicketService;
import com.fishmatrix.chat.model.ChatGroupView;
import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.chat.model.ChatImage;
import com.fishmatrix.chat.model.ChatMessage;
import com.fishmatrix.chat.model.OnlineMember;
import com.fishmatrix.chat.model.ReadRequest;
import com.fishmatrix.chat.model.HistoryPage;
import com.fishmatrix.chat.model.UnreadSummary;
import com.fishmatrix.chat.service.ChatImageService;
import com.fishmatrix.chat.service.ChatService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final WsTicketService ticketService;

    @GetMapping("/groups")
    public Map<String, Object> groups(
            @RequestAttribute(ChatAuthInterceptor.ATTR_IDENTITY) ChatIdentity viewer) {
        List<ChatGroupView> groups = chatService.groups(viewer);
        return Map.of("groups", groups);
    }

    @GetMapping("/groups/{groupId}/history")
    public Map<String, Object> history(
            @PathVariable String groupId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(required = false) Long afterSeq) {
        return historyResponse(chatService.groupHistory(groupId, beforeSeq, afterSeq, limit));
    }

    @GetMapping("/groups/{groupId}/search")
    public Map<String, Object> search(
            @PathVariable String groupId,
            @RequestParam String q,
            @RequestParam(defaultValue = "50") int limit) {
        return Map.of("messages", chatService.groupSearch(groupId, q, limit));
    }

    @GetMapping("/groups/{groupId}/members")
    public Map<String, Object> members(@PathVariable String groupId) {
        List<OnlineMember> members = chatService.members(groupId);
        return Map.of("members", members, "count", members.size());
    }

    @GetMapping("/online")
    public Map<String, Object> online() {
        List<OnlineMember> members = chatService.onlineMembers();
        return Map.of("members", members, "count", members.size());
    }

    @GetMapping("/dm/{peerUserId}/history")
    public Map<String, Object> directHistory(
            @PathVariable String peerUserId,
            @RequestAttribute(ChatAuthInterceptor.ATTR_IDENTITY) ChatIdentity viewer,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(required = false) Long afterSeq) {
        return historyResponse(chatService.directHistory(
                viewer, peerUserId, beforeSeq, afterSeq, limit));
    }

    @GetMapping("/dm/{peerUserId}/search")
    public Map<String, Object> directSearch(
            @PathVariable String peerUserId,
            @RequestAttribute(ChatAuthInterceptor.ATTR_IDENTITY) ChatIdentity viewer,
            @RequestParam String q,
            @RequestParam(defaultValue = "50") int limit) {
        return Map.of("messages", chatService.directSearch(viewer, peerUserId, q, limit));
    }

    @GetMapping("/unread")
    public UnreadSummary unread(
            @RequestAttribute(ChatAuthInterceptor.ATTR_IDENTITY) ChatIdentity viewer) {
        return chatService.unread(viewer);
    }

    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(
            @RequestAttribute(ChatAuthInterceptor.ATTR_IDENTITY) ChatIdentity viewer,
            @RequestBody ReadRequest request) {
        chatService.markRead(viewer, request.groupId(), request.peerUserId(), request.seq());
    }

    @PostMapping("/messages/{messageId}/recall")
    public Map<String, Object> recall(
            @PathVariable String messageId,
            @RequestAttribute(ChatAuthInterceptor.ATTR_IDENTITY) ChatIdentity viewer) {
        ChatMessage message = chatService.recall(viewer, messageId);
        return Map.of("message", message);
    }

    @PostMapping("/ws-ticket")
    public WsTicketService.Ticket wsTicket(
            @RequestAttribute(ChatAuthInterceptor.ATTR_IDENTITY) ChatIdentity viewer) {
        return ticketService.issue(viewer);
    }

    /**
     * 上传私聊图片。返回的 id 作为 kind=image 的私聊消息内容发送。
     * 群聊图片不上传，直接在 WebSocket 中以 data URL 实时转发。
     */
    @PostMapping("/images")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> uploadImage(
            @RequestAttribute(ChatAuthInterceptor.ATTR_IDENTITY) ChatIdentity viewer,
            @RequestParam("file") MultipartFile file) {
        ChatImage image = chatService.uploadImage(viewer, file);
        return Map.of("image", image);
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<byte[]> image(
            @PathVariable String imageId,
            @RequestAttribute(ChatAuthInterceptor.ATTR_IDENTITY) ChatIdentity viewer) {
        ChatImageService.StoredImage image = chatService.loadImage(viewer, imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(image.bytes());
    }

    private Map<String, Object> historyResponse(HistoryPage page) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("messages", page.messages());
        response.put("hasMore", page.hasMore());
        response.put("nextBeforeSeq", page.nextBeforeSeq());
        return response;
    }
}
