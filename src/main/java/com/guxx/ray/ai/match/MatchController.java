package com.guxx.ray.ai.match;

import com.guxx.ray.ai.auth.SessionPrincipal;
import com.guxx.ray.ai.common.Result;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class MatchController {
    private final MatchService matches;

    public MatchController(MatchService matches) {
        this.matches = matches;
    }

    @PostMapping("/api/v1/matches/sync")
    public Result<Void> sync(@AuthenticationPrincipal SessionPrincipal principal, @Valid @RequestBody MatchSyncDTO dto) {
        if (principal == null) throw new MatchException(HttpStatus.UNAUTHORIZED, "Valid Steam session required");
        matches.sync(principal.steamId(), dto);
        return Result.success();
    }

    @GetMapping("/api/v1/players/{steam_id}/matches")
    public Result<MatchPage> history(@PathVariable("steam_id") String steamId,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size,
                                     @RequestParam(defaultValue = "all") String filter) {
        return Result.success(matches.history(steamId, page, size, filter));
    }
}
