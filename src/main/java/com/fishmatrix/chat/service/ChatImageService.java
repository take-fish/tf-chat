package com.fishmatrix.chat.service;

import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.model.ChatException;
import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.chat.model.ChatImage;
import com.fishmatrix.chat.repository.ChatImageRepository;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 私聊图片的落盘与读取。图片文件以 UUID 命名，元数据写入 chat_image。
 * 群聊图片不经过这里，只在 WebSocket 帧中实时转发。
 */
@Service
public class ChatImageService {

    private static final Logger log = LoggerFactory.getLogger(ChatImageService.class);

    private static final Pattern IMAGE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Set<String> SUBTYPES = Set.of("png", "jpeg", "gif", "webp");

    private final ChatImageRepository repository;
    private final ChatProperties properties;
    private final Path root;

    public ChatImageService(ChatImageRepository repository, ChatProperties properties) {
        this.repository = repository;
        this.properties = properties;
        this.root = Path.of(properties.imageDir()).toAbsolutePath().normalize();
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            log.warn("无法创建聊天图片目录 {}: {}", root, exception.getMessage());
        }
    }

    public ChatImage upload(ChatIdentity owner, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ChatException.badRequest("图片不能为空");
        }
        if (file.getSize() > properties.maxImageBytes()) {
            throw ChatException.badRequest("图片过大，上限为 " + (properties.maxImageBytes() / 1024 / 1024) + "MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw ChatException.unavailable("图片读取失败");
        }
        if (bytes.length > properties.maxImageBytes()) {
            throw ChatException.badRequest("图片过大，上限为 " + (properties.maxImageBytes() / 1024 / 1024) + "MB");
        }
        String contentType = detectContentType(bytes);
        if (contentType == null) {
            throw ChatException.badRequest("仅支持 PNG、JPEG、GIF、WebP 图片");
        }

        String id = UUID.randomUUID().toString();
        Path target = resolve(id);
        try {
            Files.createDirectories(root);
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException exception) {
            throw ChatException.unavailable("图片保存失败");
        }

        ChatImage image = new ChatImage(
                id, owner.userId(), contentType, bytes.length, System.currentTimeMillis());
        try {
            return repository.insert(image);
        } catch (RuntimeException exception) {
            deleteQuietly(target);
            throw exception;
        }
    }

    public StoredImage load(ChatIdentity viewer, String imageId) {
        ChatImage image = requireImage(imageId);
        if (!image.ownerUserId().equals(viewer.userId())
                && !repository.isDirectParticipant(image.id(), viewer.userId())) {
            throw ChatException.forbidden("无权查看该图片");
        }
        Path path = resolve(image.id());
        try {
            if (!Files.exists(path)) {
                throw ChatException.notFound("图片文件不存在");
            }
            return new StoredImage(Files.readAllBytes(path), image.contentType());
        } catch (IOException exception) {
            throw ChatException.unavailable("图片读取失败");
        }
    }

    /**
     * 私聊发送图片前校验：图片必须由发送者上传。
     */
    public String requireOwned(String imageId, String ownerUserId) {
        ChatImage image = requireImage(imageId);
        if (!image.ownerUserId().equals(ownerUserId)) {
            throw ChatException.forbidden("不能发送其他账号上传的图片");
        }
        return image.id();
    }

    /**
     * 群聊图片是临时内容：只校验 data URL 与大小，不落盘、不落库。
     */
    public String validateGroupDataUrl(String content) {
        String cleaned = content == null ? "" : content.strip();
        if (!cleaned.startsWith("data:image/") || cleaned.length() > properties.maxGroupImageBytes() * 2L) {
            throw ChatException.badRequest("群聊图片格式不合法或体积过大");
        }
        int comma = cleaned.indexOf(',');
        if (comma < 0) {
            throw ChatException.badRequest("群聊图片格式不合法");
        }
        String meta = cleaned.substring("data:image/".length(), comma);
        int semicolon = meta.indexOf(';');
        if (semicolon <= 0 || !meta.substring(semicolon).equals(";base64")) {
            throw ChatException.badRequest("群聊图片编码不合法");
        }
        String subtype = meta.substring(0, semicolon);
        if (!SUBTYPES.contains(subtype)) {
            throw ChatException.badRequest("仅支持 PNG、JPEG、GIF、WebP 图片");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(cleaned.substring(comma + 1));
        } catch (IllegalArgumentException exception) {
            throw ChatException.badRequest("群聊图片编码不合法");
        }
        if (bytes.length > properties.maxGroupImageBytes()) {
            throw ChatException.badRequest("群聊图片过大");
        }
        String contentType = detectContentType(bytes);
        if (contentType == null || !contentType.equals("image/" + (subtype.equals("jpg") ? "jpeg" : subtype))) {
            throw ChatException.badRequest("图片内容与声明格式不一致");
        }
        return cleaned;
    }

    private ChatImage requireImage(String imageId) {
        String cleaned = imageId == null ? "" : imageId.strip();
        if (!IMAGE_ID.matcher(cleaned).matches()) {
            throw ChatException.badRequest("图片 ID 不合法");
        }
        ChatImage image = repository.find(cleaned);
        if (image == null) {
            throw ChatException.notFound("图片不存在");
        }
        return image;
    }

    private Path resolve(String id) {
        Path path = root.resolve(id).normalize();
        if (!path.startsWith(root)) {
            throw ChatException.badRequest("图片 ID 不合法");
        }
        return path;
    }

    private static String detectContentType(byte[] data) {
        if (startsWith(data, new int[] {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
            return "image/png";
        }
        if (startsWith(data, new int[] {0xFF, 0xD8, 0xFF})) {
            return "image/jpeg";
        }
        if (data.length >= 6
                && data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
                && data[3] == '8' && (data[4] == '7' || data[4] == '9') && data[5] == 'a') {
            return "image/gif";
        }
        if (data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] data, int[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }

    public record StoredImage(byte[] bytes, String contentType) {
    }
}
