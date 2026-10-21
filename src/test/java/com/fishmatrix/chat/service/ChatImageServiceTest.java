package com.fishmatrix.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fishmatrix.chat.config.ChatProperties;
import com.fishmatrix.chat.model.ChatException;
import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.chat.model.ChatImage;
import com.fishmatrix.chat.repository.ChatImageRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class ChatImageServiceTest {

    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00
    };

    @TempDir
    Path tempDir;

    private FakeImageRepository repository;
    private ChatImageService service;
    private ChatIdentity alice;
    private ChatIdentity bob;

    @BeforeEach
    void setUp() {
        repository = new FakeImageRepository();
        service = new ChatImageService(repository, properties());
        service.init();
        alice = ChatIdentity.of(1, "甲", "USER");
        bob = ChatIdentity.of(2, "乙", "USER");
    }

    @Test
    void uploadStoresFileAndMetadata() throws Exception {
        ChatImage image = service.upload(
                alice, new MockMultipartFile("file", "a.png", "image/png", PNG));

        assertThat(image.ownerUserId()).isEqualTo("u1");
        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.size()).isEqualTo(PNG.length);
        assertThat(Files.readAllBytes(tempDir.resolve(image.id()))).isEqualTo(PNG);
        assertThat(repository.images).containsKey(image.id());
    }

    @Test
    void uploadRejectsNonImageAndOversize() {
        assertThatThrownBy(() -> service.upload(
                        alice, new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes())))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("PNG");

        byte[] huge = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, huge, 0, PNG.length);
        assertThatThrownBy(() -> service.upload(
                        alice, new MockMultipartFile("file", "big.png", "image/png", huge)))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("过大");
    }

    @Test
    void loadAllowsOwnerAndDirectParticipant() {
        ChatImage image = service.upload(
                alice, new MockMultipartFile("file", "a.png", "image/png", PNG));

        assertThat(service.load(alice, image.id()).bytes()).isEqualTo(PNG);
        assertThatThrownBy(() -> service.load(bob, image.id()))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("无权");

        repository.participants.add("u2");
        assertThat(service.load(bob, image.id()).contentType()).isEqualTo("image/png");
    }

    @Test
    void requireOwnedRejectsForeignImage() {
        ChatImage image = service.upload(
                alice, new MockMultipartFile("file", "a.png", "image/png", PNG));

        assertThat(service.requireOwned(image.id(), "u1")).isEqualTo(image.id());
        assertThatThrownBy(() -> service.requireOwned(image.id(), "u2"))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("其他账号");
    }

    @Test
    void groupDataUrlIsValidatedWithoutStoring() {
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG);

        assertThat(service.validateGroupDataUrl(dataUrl)).isEqualTo(dataUrl);
        assertThat(repository.images).isEmpty();

        assertThatThrownBy(() -> service.validateGroupDataUrl("data:image/png;base64,AAAA"))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("格式不一致");
        assertThatThrownBy(() -> service.validateGroupDataUrl("data:text/plain;base64,aGVsbG8="))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("群聊图片");
    }

    private ChatProperties properties() {
        return new ChatProperties(
                false, 60_000, 1000, 500, 200, 2000, 32,
                "http://127.0.0.1:9", 60, 60, 120, 20, List.of("*"),
                tempDir.toString(), 5 * 1024 * 1024, 1024 * 1024);
    }

    private static final class FakeImageRepository implements ChatImageRepository {

        final Map<String, ChatImage> images = new HashMap<>();
        final java.util.Set<String> participants = new java.util.HashSet<>();

        @Override
        public ChatImage insert(ChatImage image) {
            images.put(image.id(), image);
            return image;
        }

        @Override
        public ChatImage find(String id) {
            return images.get(id);
        }

        @Override
        public boolean isDirectParticipant(String imageId, String userId) {
            return images.containsKey(imageId) && participants.contains(userId);
        }
    }
}
