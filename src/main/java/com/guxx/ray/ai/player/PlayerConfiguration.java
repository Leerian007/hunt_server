package com.guxx.ray.ai.player;

import com.guxx.ray.ai.common.SteamHttpClientFactory;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;

@Configuration
@EnableAsync
public class PlayerConfiguration {
    @Bean
    RestTemplate steamApiRestTemplate(@Value("${steam.proxy-url:}") String proxyUrl) {
        var http = SteamHttpClientFactory.create(proxyUrl);
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }

    @Bean
    ThreadPoolTaskExecutor playerProfileExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("player-profile-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
