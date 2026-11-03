package dev.workhub.onboarding;

import dev.workhub.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RateLimits {
    private final JdbcTemplate db;

    public RateLimits(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean take(String key, int maximum) {
        String hash = Inputs.hash(key);
        db.update(
                "INSERT INTO rate_limit(bucket,hits,expires_at) VALUES"
                    + " (?,1,TIMESTAMPADD(MINUTE,15,UTC_TIMESTAMP(6))) ON DUPLICATE KEY UPDATE"
                    + " hits=IF(expires_at<UTC_TIMESTAMP(6),1,hits+1),expires_at=IF(expires_at<UTC_TIMESTAMP(6),TIMESTAMPADD(MINUTE,15,UTC_TIMESTAMP(6)),expires_at)",
                hash);
        return db.queryForObject("SELECT hits FROM rate_limit WHERE bucket=?", Integer.class, hash)
                <= maximum;
    }

    public void require(String key, int max) {
        if (!take(key, max)) throw new ApiException(429, "RATE_LIMITED", "请求过于频繁，请在15分钟后重试");
    }
}
