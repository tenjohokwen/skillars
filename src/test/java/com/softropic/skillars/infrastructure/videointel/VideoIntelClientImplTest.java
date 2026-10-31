package com.softropic.skillars.infrastructure.videointel;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// S-18: the stub used to answer "clean" for every video. It must never produce an approving result.
class VideoIntelClientImplTest {

    private final VideoIntelClientImpl stub = new VideoIntelClientImpl(new RestTemplate(), "p", 0.7);

    @Test
    void stub_neverYieldsAVerdict_itFailsClosed() {
        assertThatThrownBy(() -> stub.detectExplicitContent("https://cdn.example.com/v.mp4"))
            .isInstanceOf(VideoIntelException.class);
    }

    @Test
    void stub_isNotARealScanner() {
        assertThat(stub.isRealScanner()).isFalse();
    }
}
