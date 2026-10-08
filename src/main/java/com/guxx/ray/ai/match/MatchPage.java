package com.guxx.ray.ai.match;

import java.util.List;

public record MatchPage(long total, int pages, List<MatchView> list) {
}
